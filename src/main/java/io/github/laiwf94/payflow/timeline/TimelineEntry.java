package io.github.laiwf94.payflow.timeline;

import java.time.Instant;
import java.util.UUID;

/**
 * One event in an order's history, as the merchant sees it. {@code refund} is present on a refund
 * event.
 */
public record TimelineEntry(
    long seq,
    String eventType,
    String state,
    String failureCode,
    Refund refund,
    Instant occurredAt) {

  /** The refund a refund event is about; {@code failureCode} only when the provider refused it. */
  public record Refund(UUID id, long amount, String failureCode) {}
}
