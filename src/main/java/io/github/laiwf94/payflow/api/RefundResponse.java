package io.github.laiwf94.payflow.api;

import io.github.laiwf94.payflow.refund.Refund;
import io.github.laiwf94.payflow.refund.RefundAllocation;
import io.github.laiwf94.payflow.refund.RefundLine;
import io.github.laiwf94.payflow.refund.RefundState;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A refund as the API returns it. {@code lines} is present for a refund of goods; {@code failure}
 * when the provider refused any of it; {@code refunded} says how much went back; {@code tenders}
 * how it was split between the order's tenders, each with how its share ended. The worker's
 * bookkeeping (leases, attempts) stays internal.
 */
public record RefundResponse(
    UUID id,
    UUID orderId,
    String state,
    long amount,
    long refunded,
    String currency,
    List<RefundLine> lines,
    String allocationPolicy,
    List<Share> tenders,
    Failure failure,
    Instant createdAt,
    Instant updatedAt) {

  public record Failure(String code, String message) {}

  /** One tender's share of the refund. */
  public record Share(
      int tender, String paymentMethod, long amount, String state, Failure failure) {

    static Share of(RefundAllocation a) {
      return new Share(
          a.position(),
          a.method(),
          a.amountMinor(),
          a.state().name(),
          a.failureCode() == null ? null : new Failure(a.failureCode(), a.failureMessage()));
    }
  }

  public static RefundResponse of(Refund r, String currency) {
    return new RefundResponse(
        r.id(),
        r.orderId(),
        r.state().name(),
        r.amountMinor(),
        r.state() == RefundState.PENDING ? 0 : r.returnedMinor(),
        currency,
        r.lines().isEmpty() ? null : r.lines(),
        r.allocationPolicy() == null ? null : r.allocationPolicy().code(),
        r.allocations().isEmpty() ? null : r.allocations().stream().map(Share::of).toList(),
        r.failureCode() == null ? null : new Failure(r.failureCode(), r.failureMessage()),
        r.createdAt(),
        r.updatedAt());
  }
}
