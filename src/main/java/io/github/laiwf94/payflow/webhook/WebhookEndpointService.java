package io.github.laiwf94.payflow.webhook;

import io.github.laiwf94.payflow.security.SecretBox;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Registers, changes and deletes merchants' webhook endpoints. Each endpoint gets its own signing
 * secret, generated here and returned once, on creation or rotation; after that it lives only
 * sealed in its row (see {@link SecretBox}) and is opened only to sign deliveries.
 *
 * <p>It also keeps each endpoint's health: a failed attempt starts a run of failures, a 2xx ends
 * it, and an endpoint whose run lasts long enough is disabled with a final notice (see {@link
 * #failed}).
 */
@Service
public class WebhookEndpointService {

  /** Endpoints one merchant may have at a time, as Stripe allows. */
  public static final int MAX_ENDPOINTS = 16;

  public static final int MAX_DESCRIPTION = 256;

  /** How long a rotated-out secret keeps signing when the request doesn't say: a day. */
  public static final Duration DEFAULT_PREVIOUS_SECRET_TTL = Duration.ofDays(1);

  /** The longest a rotated-out secret may keep signing. */
  public static final Duration MAX_PREVIOUS_SECRET_TTL = Duration.ofDays(7);

  /**
   * How much shorter than the disable time a run of failures may be when a delivery has just failed
   * its whole retry window, see {@link #failed}.
   */
  public static final Duration WINDOW_SLACK = Duration.ofHours(1);

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

  /** An endpoint with its new signing secret, which is never shown again. */
  public record Rotated(WebhookEndpoint endpoint, String secret) {}

  /**
   * An endpoint payflow has just disabled, and the final notice it queued.
   *
   * @param notices the deliveries of the notice: one to the endpoint itself, and one to each of the
   *     merchant's other enabled endpoints that subscribe to it
   */
  public record Disabled(WebhookEndpoint endpoint, UUID eventId, int notices) {}

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
  private final ObjectMapper json;
  private final SecureRandom random = new SecureRandom();

  public WebhookEndpointService(
      WebhookEndpointRepository repo,
      WebhookDeliveryRepository deliveries,
      SecretBox box,
      Clock clock,
      SafeHttpSender sender,
      ObjectMapper json) {
    this.repo = repo;
    this.deliveries = deliveries;
    this.box = box;
    this.clock = clock;
    this.sender = sender;
    this.json = json;
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
        WebhookEndpoint.created(UUID.randomUUID(), merchant, normalUrl, desc, events, now);
    String secret = newSecret();
    repo.insert(e, box.seal(secret, WebhookEndpointRepository.secretContext(merchant, e.id())));
    return new Created(e, secret);
  }

  @Transactional(readOnly = true)
  public List<WebhookEndpoint> list(String merchant) {
    Instant now = clock.instant();
    return repo.list(merchant).stream().map(e -> e.at(now)).toList();
  }

  /**
   * One of the merchant's endpoints.
   *
   * @throws NotFound when the merchant has no such endpoint; it doesn't roll back a transaction
   *     this joins, so a caller's answer can still be stored with it
   */
  @Transactional(readOnly = true, noRollbackFor = NotFound.class)
  public WebhookEndpoint get(String merchant, UUID id) {
    return repo.find(merchant, id).orElseThrow(NotFound::new).at(clock.instant());
  }

  /**
   * Applies {@code changes}. The secret stays as it is. Enabling an endpoint payflow disabled gives
   * it a fresh start: its run of failures is forgotten.
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
    Instant now = clock.instant();
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
            before.disabledReason(),
            before.failingSince(),
            before.previousSecretExpiresAt(),
            before.createdAt(),
            now);
    // A concurrent delete between the read and the write leaves nothing to update.
    return repo.update(after).orElseThrow(NotFound::new).at(now);
  }

  /**
   * Gives the endpoint a new signing secret, returned this once. The secret it replaces keeps
   * signing beside the new one for {@code previousTtl}, so each delivery carries both signatures
   * while the merchant deploys the new secret; zero stops the old one at once. A secret from an
   * earlier rotation that was still signing stops, so at most two ever sign.
   *
   * @param previousTtl how long the replaced secret keeps signing, 0 to 7 days; null for a day
   * @throws IllegalArgumentException when {@code previousTtl} is out of range
   * @throws NotFound when the merchant has no such endpoint
   */
  @Transactional(noRollbackFor = {IllegalArgumentException.class, NotFound.class})
  public Rotated rotateSecret(String merchant, UUID id, Duration previousTtl) {
    Duration ttl = previousTtl == null ? DEFAULT_PREVIOUS_SECRET_TTL : previousTtl;
    if (ttl.isNegative() || ttl.compareTo(MAX_PREVIOUS_SECRET_TTL) > 0) {
      throw new IllegalArgumentException(
          "previous_secret_expires_in: must be 0 to "
              + MAX_PREVIOUS_SECRET_TTL.toSeconds()
              + " seconds (7 days)");
    }
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    String secret = newSecret();
    WebhookEndpoint e =
        repo.rotate(
                merchant,
                id,
                box.seal(secret, WebhookEndpointRepository.secretContext(merchant, id)),
                ttl.isZero() ? null : now.plus(ttl),
                now)
            .orElseThrow(NotFound::new);
    return new Rotated(e.at(now), secret);
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
   * The endpoint's signing secrets at {@code now}, opened from its row: the current one first, then
   * the one it replaced while that still signs.
   *
   * @throws NotFound when the merchant has no such endpoint
   * @throws SecretBox.CannotOpen when a key that sealed one is no longer configured
   */
  @Transactional(readOnly = true)
  public List<String> signingSecrets(String merchant, UUID id, Instant now) {
    List<SecretBox.Sealed> sealed = repo.secrets(merchant, id, now);
    if (sealed.isEmpty()) {
      throw new NotFound();
    }
    String context = WebhookEndpointRepository.secretContext(merchant, id);
    List<String> out = new ArrayList<>(sealed.size());
    for (SecretBox.Sealed s : sealed) {
      out.add(box.open(s, context));
    }
    return out;
  }

  /** Wipes the secrets rotations left behind once they stop signing. Returns how many. */
  @Transactional
  public int wipeExpiredSecrets() {
    return repo.wipeExpiredSecrets(clock.instant());
  }

  /** The endpoint answered with a 2xx: whatever run of failures it had is over. */
  @Transactional
  public void succeeded(UUID endpointId) {
    repo.markHealthy(endpointId);
  }

  /**
   * An attempt to deliver {@code claim} reached the endpoint, or tried to, and failed. The first
   * failure starts the endpoint's run of failures. When that run began at least {@code
   * disableAfter} ago, payflow disables the endpoint and queues a final {@value
   * WebhookEvents#ENDPOINT_DISABLED} notice: once to the endpoint itself, and to each of the
   * merchant's other enabled endpoints that subscribe to it.
   *
   * <p>When {@code windowSpent} says the delivery was just given up at the end of its retry window,
   * {@link #WINDOW_SLACK} less is enough: a delivery's first attempt can come a little after its
   * event, so an endpoint that failed every attempt of a three-day window may have failed for a
   * little under three days, and without the slack an endpoint with one event would never be
   * disabled. A first attempt much later than that (payflow was down) is not the endpoint's fault,
   * and doesn't count.
   *
   * <p>Only failures that are the endpoint's count: the caller reports none for an attempt payflow
   * cut short itself, or that never got as far as sending.
   *
   * @param at now, by the dispatcher's clock, which the failure times come from too
   * @return the endpoint and its notice, when this call disabled it
   */
  @Transactional
  public Optional<Disabled> failed(
      WebhookDeliveryRepository.Claim claim,
      Instant attemptedAt,
      boolean windowSpent,
      Duration disableAfter,
      Instant at) {
    Instant now = at.truncatedTo(ChronoUnit.MICROS);
    repo.markFailing(claim.endpointId(), attemptedAt);
    Duration needed = windowSpent ? disableAfter.minus(WINDOW_SLACK) : disableAfter;
    Optional<WebhookEndpoint> off =
        repo.disableIfFailingSince(claim.endpointId(), now.minus(needed), now);
    if (off.isEmpty()) {
      return Optional.empty();
    }
    WebhookEndpoint e = off.get();
    UUID eventId = UUID.randomUUID();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("event_id", eventId);
    payload.put("event_type", WebhookEvents.ENDPOINT_DISABLED);
    payload.put("merchant_id", e.merchantId());
    payload.put("endpoint_id", e.id());
    payload.put("url", e.url());
    payload.put("reason", "failing");
    payload.put("failing_since", e.failingSince().toString());
    payload.put("created_at", now.toString());
    int notices =
        deliveries.enqueueDisabledNotice(
            e.merchantId(), e.id(), eventId, json.writeValueAsString(payload), now);
    return Optional.of(new Disabled(e, eventId, notices));
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
