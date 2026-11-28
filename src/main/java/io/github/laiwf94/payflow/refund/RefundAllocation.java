package io.github.laiwf94.payflow.refund;

import java.time.Instant;
import java.util.UUID;

/**
 * One tender's share of a refund: {@code amountMinor} returned to the tender at {@code position},
 * which the provider knows as {@code providerPaymentId}. Shares are made in {@code seq} order, each
 * at its own tender's provider payment, and settle on their own; the refund settles once they all
 * have. The refund's id is the reference a share is made under, unique at its payment because a
 * refund has at most one share per tender.
 *
 * @param state {@code PENDING}, then {@code SUCCEEDED} or {@code FAILED}
 * @param providerPaymentId the tender's payment at the provider, absent if it was never recorded
 */
public record RefundAllocation(
    UUID refundId,
    UUID paymentId,
    int seq,
    int position,
    String method,
    String providerPaymentId,
    long amountMinor,
    RefundState state,
    String providerRefundId,
    String failureCode,
    String failureMessage,
    Instant createdAt,
    Instant updatedAt) {

  public RefundAllocation {
    if (state == RefundState.PARTIALLY_FAILED) {
      throw new IllegalArgumentException("a share succeeds or fails whole");
    }
  }
}
