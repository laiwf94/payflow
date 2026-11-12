package io.github.laiwf94.payflow.refund;

import io.github.laiwf94.payflow.order.Order;
import io.github.laiwf94.payflow.order.OrderTransitions;
import io.github.laiwf94.payflow.outbox.OutboxWriter;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Starts and settles refunds, each with its event in the order's stream. A succeeded refund, its
 * {@code refund.succeeded} event and its order's new refunded total, state and event commit
 * together, so the order never shows money back that no refund row accounts for, or the reverse.
 */
@Component
public class RefundTransitions {

  /** The types of the events a refund writes into its order's stream. */
  public static final String CREATED = "refund.created";

  public static final String SUCCEEDED = "refund.succeeded";
  public static final String FAILED = "refund.failed";

  private final RefundRepository refunds;
  private final OrderTransitions orders;

  public RefundTransitions(RefundRepository refunds, OrderTransitions orders) {
    this.refunds = refunds;
    this.orders = orders;
  }

  /**
   * Stores a new {@code PENDING} refund and its {@code refund.created} event. Returns its order as
   * it now is. The caller holds the order's lock and has sized the refund under it.
   */
  @Transactional
  public Order create(Refund refund, Instant now) {
    refunds.insert(refund);
    return orders.recordRefundEvent(refund.orderId(), CREATED, details(refund), now);
  }

  /**
   * Records {@code refund} as made: its {@code refund.succeeded} event, then the order's refunded
   * total, state and event. Empty, writing nothing, when the lease was lost.
   */
  @Transactional
  public Optional<Refund> succeed(
      Refund refund, UUID leaseToken, String providerRefundId, Instant now) {
    Optional<Refund> after = refunds.succeed(refund.id(), leaseToken, providerRefundId, now);
    after.ifPresent(
        r -> {
          orders.recordRefundEvent(r.orderId(), SUCCEEDED, details(r), now);
          orders.recordRefund(r.orderId(), r.amountMinor(), now);
        });
    return after;
  }

  /**
   * Records {@code refund} as refused, with its {@code refund.failed} event. Empty, writing
   * nothing, when the lease was lost.
   */
  @Transactional
  public Optional<Refund> fail(
      Refund refund, UUID leaseToken, String code, String message, Instant now) {
    Optional<Refund> after = refunds.fail(refund.id(), leaseToken, code, message, now);
    after.ifPresent(r -> orders.recordRefundEvent(r.orderId(), FAILED, details(r), now));
    return after;
  }

  /** What the refund's events say about it. */
  static OutboxWriter.RefundDetails details(Refund r) {
    return new OutboxWriter.RefundDetails(
        r.id(), r.amountMinor(), r.lines(), r.state().name(), r.failureCode());
  }
}
