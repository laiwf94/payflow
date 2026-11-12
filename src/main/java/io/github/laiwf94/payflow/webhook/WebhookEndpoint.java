package io.github.laiwf94.payflow.webhook;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A merchant's webhook endpoint as stored, without its secret: that is sealed in the row and read
 * only to sign a delivery, see {@link WebhookEndpointRepository#secret}.
 */
public record WebhookEndpoint(
    UUID id,
    String merchantId,
    String url,
    String description,
    List<String> enabledEvents,
    State state,
    Instant createdAt,
    Instant updatedAt) {

  /** {@code DISABLED} endpoints keep their settings and history but receive nothing. */
  public enum State {
    ENABLED,
    DISABLED
  }

  public WebhookEndpoint {
    enabledEvents = List.copyOf(enabledEvents);
  }
}
