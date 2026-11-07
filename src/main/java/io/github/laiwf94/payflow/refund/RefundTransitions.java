package io.github.laiwf94.payflow.refund;

import io.github.laiwf94.payflow.order.OrderTransitions;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Settles refunds. A succeeded refund and its order's new refunded total, state and event commit
 * together, so the order never shows money back that no refund row accounts for, or the reverse.
 */
@Component
public class RefundTransitions {

  private final RefundRepository refunds;
  private final OrderTransitions orders;

  public RefundTransitions(RefundRepository refunds, OrderTransitions orders) {
    this.refunds = refunds;
    this.orders = orders;
  }

  /** Records {@code refund} as made. Empty, writing nothing, when the lease was lost. */
  @Transactional
  public Optional<Refund> succeed(
      Refund refund, UUID leaseToken, String providerRefundId, Instant now) {
    Optional<Refund> after = refunds.succeed(refund.id(), leaseToken, providerRefundId, now);
    after.ifPresent(r -> orders.recordRefund(r.orderId(), r.amountMinor(), now));
    return after;
  }

  /** Records {@code refund} as refused. Empty when the lease was lost. */
  @Transactional
  public Optional<Refund> fail(
      Refund refund, UUID leaseToken, String code, String message, Instant now) {
    return refunds.fail(refund.id(), leaseToken, code, message, now);
  }
}
