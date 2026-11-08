package io.github.laiwf94.payflow.api;

import io.github.laiwf94.payflow.refund.Refund;
import io.github.laiwf94.payflow.refund.RefundLine;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A refund as the API returns it. {@code lines} is present for a refund of goods; {@code failure}
 * when the provider refused it. The worker's bookkeeping (leases, attempts) stays internal.
 */
public record RefundResponse(
    UUID id,
    UUID orderId,
    String state,
    long amount,
    String currency,
    List<RefundLine> lines,
    Failure failure,
    Instant createdAt,
    Instant updatedAt) {

  public record Failure(String code, String message) {}

  public static RefundResponse of(Refund r, String currency) {
    return new RefundResponse(
        r.id(),
        r.orderId(),
        r.state().name(),
        r.amountMinor(),
        currency,
        r.lines().isEmpty() ? null : r.lines(),
        r.failureCode() == null ? null : new Failure(r.failureCode(), r.failureMessage()),
        r.createdAt(),
        r.updatedAt());
  }
}
