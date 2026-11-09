package io.github.laiwf94.payflow.timeline;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The order event as published to Kafka. Unknown fields are ignored when reading. A refund event
 * ({@code refund.*}) also carries the {@link Refund} it is about.
 */
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
    Refund refund,
    OffsetDateTime occurredAt) {

  /** The refund a refund event is about, as it was after the event. */
  public record Refund(UUID id, Long amount, String state, String failureCode) {}

  static final String REFUND_PREFIX = "refund.";

  /** True when every field the consumer relies on is present. */
  boolean complete() {
    return eventId != null
        && eventType != null
        && orderId != null
        && merchantId != null
        && seq != null
        && state != null
        && occurredAt != null
        && (!eventType.startsWith(REFUND_PREFIX)
            || (refund != null && refund.id() != null && refund.amount() != null));
  }
}
