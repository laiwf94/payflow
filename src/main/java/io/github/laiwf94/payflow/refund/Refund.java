package io.github.laiwf94.payflow.refund;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One refund of an order, as stored, including the bookkeeping its worker needs. {@code lines} is
 * empty for a refund of an amount; otherwise it lists the goods given back.
 */
public record Refund(
    UUID id,
    UUID orderId,
    long amountMinor,
    List<RefundLine> lines,
    RefundState state,
    String providerRefundId,
    String failureCode,
    String failureMessage,
    int attempt,
    Instant nextAttemptAt,
    Instant createdAt,
    Instant updatedAt) {

  public Refund {
    lines = lines == null ? List.of() : List.copyOf(lines);
  }

  /** A new refund of {@code amountMinor}, due immediately. */
  public static Refund pending(
      UUID id, UUID orderId, long amountMinor, List<RefundLine> lines, Instant now) {
    return new Refund(
        id, orderId, amountMinor, lines, RefundState.PENDING, null, null, null, 0, now, now, now);
  }
}
