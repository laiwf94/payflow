package io.github.laiwf94.payflow.timeline;

import java.time.OffsetDateTime;
import java.util.UUID;

/** The order event as published to Kafka. Unknown fields are ignored when reading. */
public record OrderEvent(
    UUID eventId,
    String eventType,
    UUID orderId,
    String merchantId,
    Long seq,
    String state,
    String previousState,
    Long amount,
    String currency,
    String failureCode,
    OffsetDateTime occurredAt) {

  /** True when every field the consumer relies on is present. */
  boolean complete() {
    return eventId != null
        && eventType != null
        && orderId != null
        && merchantId != null
        && seq != null
        && state != null
        && occurredAt != null;
  }
}
