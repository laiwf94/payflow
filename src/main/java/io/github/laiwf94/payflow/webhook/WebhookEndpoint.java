package io.github.laiwf94.payflow.webhook;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A merchant's webhook endpoint as stored, without its secrets: those are sealed in the row and
 * read only to sign a delivery, see {@link WebhookEndpointRepository#secrets}.
 *
 * @param disabledReason why a {@code DISABLED} endpoint is disabled; null while it is enabled
 * @param failingSince when its current run of failed attempts began; null after a 2xx, and before
 *     the first failure
 * @param previousSecretExpiresAt until when the secret it had before its last rotation still signs
 *     deliveries beside the current one; null when there is no such secret, or it has expired
 */
public record WebhookEndpoint(
    UUID id,
    String merchantId,
    String url,
    String description,
    List<String> enabledEvents,
    State state,
    DisabledReason disabledReason,
    Instant failingSince,
    Instant previousSecretExpiresAt,
    Instant createdAt,
    Instant updatedAt) {

  /** {@code DISABLED} endpoints keep their settings and history but receive nothing. */
  public enum State {
    ENABLED,
    DISABLED
  }

  /** Who disabled an endpoint. */
  public enum DisabledReason {
    /** The merchant, through the API. */
    MERCHANT,
    /** payflow, because every attempt to deliver to it failed for days. */
    FAILING
  }

  public WebhookEndpoint {
    enabledEvents = List.copyOf(enabledEvents);
    if (state == State.ENABLED) {
      disabledReason = null;
    } else if (disabledReason == null) {
      disabledReason = DisabledReason.MERCHANT;
    }
  }

  /** A new, enabled endpoint that has never failed and has one secret. */
  public static WebhookEndpoint created(
      UUID id,
      String merchantId,
      String url,
      String description,
      List<String> enabledEvents,
      Instant now) {
    return new WebhookEndpoint(
        id, merchantId, url, description, enabledEvents, State.ENABLED, null, null, null, now, now);
  }

  /** This endpoint as seen at {@code now}: a previous secret that has expired is not shown. */
  WebhookEndpoint at(Instant now) {
    if (previousSecretExpiresAt == null || previousSecretExpiresAt.isAfter(now)) {
      return this;
    }
    return new WebhookEndpoint(
        id,
        merchantId,
        url,
        description,
        enabledEvents,
        state,
        disabledReason,
        failingSince,
        null,
        createdAt,
        updatedAt);
  }
}
