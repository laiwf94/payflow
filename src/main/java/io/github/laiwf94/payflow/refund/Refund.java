package io.github.laiwf94.payflow.refund;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One refund of an order, as stored, including the bookkeeping its worker needs. {@code lines} is
 * empty for a refund of an amount; otherwise it lists the goods given back.
 *
 * @param returnedMinor what went back to the customer: 0 while pending or once failed, the whole
 *     amount once succeeded, and the succeeded shares' sum after a partial failure
 * @param allocationPolicy how the refund was split between the order's tenders; absent until it was
 *     (a refund an older binary created gets its shares when the worker first claims it)
 * @param allocations each tender's share, in the order they are made
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
    Instant updatedAt,
    long returnedMinor,
    AllocationPolicy allocationPolicy,
    List<RefundAllocation> allocations) {

  public Refund {
    lines = lines == null ? List.of() : List.copyOf(lines);
    allocations = allocations == null ? List.of() : List.copyOf(allocations);
  }

  /** A new refund of {@code amountMinor}, due immediately, with no shares yet. */
  public static Refund pending(
      UUID id, UUID orderId, long amountMinor, List<RefundLine> lines, Instant now) {
    return new Refund(
        id,
        orderId,
        amountMinor,
        lines,
        RefundState.PENDING,
        null,
        null,
        null,
        0,
        now,
        now,
        now,
        0,
        null,
        List.of());
  }

  /**
   * What a settled refund returned when the database doesn't say: everything for {@code SUCCEEDED},
   * nothing otherwise. Refunds an older binary settled record no amount.
   */
  static long returnedFor(RefundState state, long amountMinor, Long stored) {
    if (stored != null) {
      return stored;
    }
    return state == RefundState.SUCCEEDED ? amountMinor : 0;
  }

  /**
   * What the refund counts against its order's capture: the whole amount while pending or once
   * succeeded, nothing once failed, and only what came back after a partial failure.
   */
  public long activeMinor() {
    return switch (state) {
      case PENDING, SUCCEEDED -> amountMinor;
      case FAILED -> 0;
      case PARTIALLY_FAILED -> returnedMinor;
    };
  }

  /** The refund with {@code shares} as its allocations, split by {@code policy}. */
  public Refund withAllocations(AllocationPolicy policy, List<RefundAllocation> shares) {
    return new Refund(
        id,
        orderId,
        amountMinor,
        lines,
        state,
        providerRefundId,
        failureCode,
        failureMessage,
        attempt,
        nextAttemptAt,
        createdAt,
        updatedAt,
        returnedMinor,
        policy,
        shares);
  }
}
