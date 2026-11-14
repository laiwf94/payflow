package io.github.laiwf94.payflow.api;

import io.github.laiwf94.payflow.webhook.WebhookEndpoint;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * An endpoint as the API returns it. {@code secret} is present only in the answer that created it
 * or rotated its secret: it is the one time the merchant sees it. {@code disabled_reason} is
 * present while it is disabled, {@code failing_since} while it is failing, and {@code
 * previous_secret_expires_at} while the secret a rotation replaced still signs.
 */
record WebhookEndpointResponse(
    UUID id,
    String url,
    String description,
    List<String> enabledEvents,
    String state,
    String disabledReason,
    Instant failingSince,
    String secret,
    Instant previousSecretExpiresAt,
    Instant createdAt,
    Instant updatedAt) {

  static WebhookEndpointResponse of(WebhookEndpoint e) {
    return of(e, null);
  }

  static WebhookEndpointResponse of(WebhookEndpoint e, String secret) {
    return new WebhookEndpointResponse(
        e.id(),
        e.url(),
        e.description(),
        e.enabledEvents(),
        e.state().name(),
        e.disabledReason() == null ? null : e.disabledReason().name().toLowerCase(Locale.ROOT),
        e.failingSince(),
        secret,
        e.previousSecretExpiresAt(),
        e.createdAt(),
        e.updatedAt());
  }
}
