package io.github.laiwf94.payflow.webhook;

import io.github.laiwf94.payflow.security.SecretBox;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registers, changes and deletes merchants' webhook endpoints. Each endpoint gets its own signing
 * secret, generated here and returned once, on creation; after that it lives only sealed in its row
 * (see {@link SecretBox}) and is opened only to sign deliveries.
 */
@Service
public class WebhookEndpointService {

  /** Endpoints one merchant may have at a time, as Stripe allows. */
  public static final int MAX_ENDPOINTS = 16;

  public static final int MAX_DESCRIPTION = 256;

  /** {@code whsec_} and 32 random bytes, base64url. */
  static final String SECRET_PREFIX = "whsec_";

  /** No such endpoint for this merchant, or it was deleted. */
  public static final class NotFound extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public NotFound() {
      super("webhook endpoint not found");
    }
  }

  /** The merchant already has {@value #MAX_ENDPOINTS} endpoints. */
  public static final class LimitReached extends RuntimeException {
    private static final long serialVersionUID = 1L;

    LimitReached() {
      super(
          "a merchant can have at most " + MAX_ENDPOINTS + " webhook endpoints; delete one first");
    }
  }

  /** A new endpoint and its signing secret, which is never shown again. */
  public record Created(WebhookEndpoint endpoint, String secret) {}

  /** What an update changes; null leaves a field as it is. An empty description clears it. */
  public record Changes(
      String url, List<String> enabledEvents, String description, WebhookEndpoint.State state) {

    boolean isEmpty() {
      return url == null && enabledEvents == null && description == null && state == null;
    }
  }

  private final WebhookEndpointRepository repo;
  private final WebhookDeliveryRepository deliveries;
  private final SecretBox box;
  private final Clock clock;
  private final SafeHttpSender sender;
  private final SecureRandom random = new SecureRandom();

  public WebhookEndpointService(
      WebhookEndpointRepository repo,
      WebhookDeliveryRepository deliveries,
      SecretBox box,
      Clock clock,
      SafeHttpSender sender) {
    this.repo = repo;
    this.deliveries = deliveries;
    this.box = box;
    this.clock = clock;
    this.sender = sender;
  }

  /**
   * Registers an endpoint, enabled, with a new secret.
   *
   * @throws IllegalArgumentException when the URL, filter or description is invalid
   * @throws LimitReached when the merchant already has {@value #MAX_ENDPOINTS}
   */
  @Transactional(noRollbackFor = {IllegalArgumentException.class, LimitReached.class})
  public Created create(
      String merchant, String url, List<String> enabledEvents, String description) {
    String normalUrl = checkedUrl(url);
    List<String> events = WebhookEvents.normalize(enabledEvents);
    String desc = description(description);
    repo.lockMerchant(merchant);
    if (repo.countLive(merchant) >= MAX_ENDPOINTS) {
      throw new LimitReached();
    }
    Instant now = clock.instant();
    WebhookEndpoint e =
        new WebhookEndpoint(
            UUID.randomUUID(),
            merchant,
            normalUrl,
            desc,
            events,
            WebhookEndpoint.State.ENABLED,
            now,
            now);
    String secret = newSecret();
    repo.insert(e, box.seal(secret, WebhookEndpointRepository.secretContext(merchant, e.id())));
    return new Created(e, secret);
  }

  @Transactional(readOnly = true)
  public List<WebhookEndpoint> list(String merchant) {
    return repo.list(merchant);
  }

  /**
   * One of the merchant's endpoints.
   *
   * @throws NotFound when the merchant has no such endpoint; it doesn't roll back a transaction
   *     this joins, so a caller's answer can still be stored with it
   */
  @Transactional(readOnly = true, noRollbackFor = NotFound.class)
  public WebhookEndpoint get(String merchant, UUID id) {
    return repo.find(merchant, id).orElseThrow(NotFound::new);
  }

  /**
   * Applies {@code changes}. The secret stays as it is.
   *
   * @throws IllegalArgumentException when nothing changes, or a new value is invalid
   * @throws NotFound when the merchant has no such endpoint
   */
  @Transactional(noRollbackFor = {IllegalArgumentException.class, NotFound.class})
  public WebhookEndpoint update(String merchant, UUID id, Changes changes) {
    if (changes.isEmpty()) {
      throw new IllegalArgumentException(
          "give at least one of url, enabled_events, description or state");
    }
    String url = changes.url() == null ? null : checkedUrl(changes.url());
    List<String> events =
        changes.enabledEvents() == null ? null : WebhookEvents.normalize(changes.enabledEvents());
    WebhookEndpoint before = repo.find(merchant, id).orElseThrow(NotFound::new);
    WebhookEndpoint after =
        new WebhookEndpoint(
            before.id(),
            before.merchantId(),
            url == null ? before.url() : url,
            changes.description() == null
                ? before.description()
                : description(changes.description()),
            events == null ? before.enabledEvents() : events,
            changes.state() == null ? before.state() : changes.state(),
            before.createdAt(),
            clock.instant());
    // A concurrent delete between the read and the write leaves nothing to update.
    return repo.update(after).orElseThrow(NotFound::new);
  }

  /**
   * Deletes the endpoint: it receives nothing more, its secret is wiped, and the API no longer
   * finds it.
   *
   * @throws NotFound when the merchant has no such endpoint, or it was already deleted
   */
  @Transactional(noRollbackFor = NotFound.class)
  public void delete(String merchant, UUID id) {
    Instant now = clock.instant();
    if (!repo.delete(merchant, id, now)) {
      throw new NotFound();
    }
    // Nothing more goes to it, so nothing should wait to.
    deliveries.failPending(id, now);
  }

  /**
   * The endpoint's signing secret, opened from its row, for signing a delivery.
   *
   * @throws NotFound when the merchant has no such endpoint
   * @throws SecretBox.CannotOpen when the key that sealed it is no longer configured
   */
  @Transactional(readOnly = true)
  public String signingSecret(String merchant, UUID id) {
    SecretBox.Sealed sealed = repo.secret(merchant, id).orElseThrow(NotFound::new);
    return box.open(sealed, WebhookEndpointRepository.secretContext(merchant, id));
  }

  /**
   * The URL as stored, refused if its host is an address webhooks may not go to or resolves to one
   * now. A host that doesn't resolve yet is accepted; every send checks again.
   */
  private String checkedUrl(String url) {
    String normal = WebhookUrls.normalize(url);
    sender
        .refusal(normal)
        .ifPresent(
            why -> {
              throw new IllegalArgumentException("url: " + why);
            });
    return normal;
  }

  /** Null for no description; empty clears one; otherwise 1-256 characters, no control chars. */
  private static String description(String d) {
    if (d == null || d.isEmpty()) {
      return null;
    }
    if (d.length() > MAX_DESCRIPTION || d.chars().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException(
          "description: must be at most "
              + MAX_DESCRIPTION
              + " characters without control characters");
    }
    return d;
  }

  private String newSecret() {
    byte[] b = new byte[32];
    random.nextBytes(b);
    return SECRET_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }
}
