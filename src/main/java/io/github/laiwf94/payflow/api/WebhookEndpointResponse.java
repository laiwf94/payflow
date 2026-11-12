package io.github.laiwf94.payflow.api;

import io.github.laiwf94.payflow.webhook.WebhookEndpoint;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An endpoint as the API returns it. {@code secret} is present only in the answer that created it:
 * it is the one time the merchant sees it.
 */
record WebhookEndpointResponse(
    UUID id,
    String url,
    String description,
    List<String> enabledEvents,
    String state,
    String secret,
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
        secret,
        e.createdAt(),
        e.updatedAt());
  }
}
