package io.github.laiwf94.payflow.refund;

import io.github.laiwf94.payflow.order.Order;
import io.github.laiwf94.payflow.order.OrderRepository;
import io.github.laiwf94.payflow.order.OrderTransitions;
import io.github.laiwf94.payflow.outbox.OutboxWriter;
import io.github.laiwf94.payflow.payment.PaymentRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Starts, splits and settles refunds, each with its event in the order's stream. A settled refund,
 * its event, what each tender returned and its order's new refunded total, state and event commit
 * together, so the order never shows money back that no refund row accounts for, or the reverse,
 * and the order's total stays the sum of its tenders'.
 */
@Component
public class RefundTransitions {

  /** The types of the events a refund writes into its order's stream. */
  public static final String CREATED = "refund.created";

  public static final String SUCCEEDED = "refund.succeeded";

  /**
   * Written when a refund ends with anything refused: {@code FAILED}, or {@code PARTIALLY_FAILED},
   * whose event also says how much was {@code refunded}.
   */
  public static final String FAILED = "refund.failed";

  private final RefundRepository refunds;
  private final OrderTransitions orders;
  private final OrderRepository orderRows;
  private final PaymentRepository payments;

  public RefundTransitions(
      RefundRepository refunds,
      OrderTransitions orders,
      OrderRepository orderRows,
      PaymentRepository payments) {
    this.refunds = refunds;
    this.orders = orders;
    this.orderRows = orderRows;
    this.payments = payments;
  }

  /**
   * How {@code policy} splits {@code amountMinor} of order {@code orderId} between its tenders,
   * given what each has captured and what other refunds' shares already take from it. The caller
   * holds the order's lock, which makes those sums exact.
   *
   * @throws IllegalArgumentException when the tenders can't return that much between them
   */
  public List<AllocationPolicy.Share> split(
      UUID orderId, long amountMinor, AllocationPolicy policy) {
    Map<UUID, Long> taken = refunds.activeByTender(orderId);
    List<AllocationPolicy.Balance> balances =
        payments.forOrder(orderId).stream()
            .filter(p -> p.capturedMinor() > 0)
            .map(
                p ->
                    new AllocationPolicy.Balance(
                        p.id(),
                        p.position(),
                        p.method(),
                        Math.max(0, p.capturedMinor() - taken.getOrDefault(p.id(), 0L))))
            .toList();
    return policy.allocate(amountMinor, balances);
  }

  /**
   * Stores a new {@code PENDING} refund, split by {@code policy}, and its {@code refund.created}
   * event. Returns the refund with its shares and its order as it now is. The caller holds the
   * order's lock and has sized the refund under it.
   */
  @Transactional
  public Created create(Refund refund, AllocationPolicy policy, Instant now) {
    List<AllocationPolicy.Share> shares = split(refund.orderId(), refund.amountMinor(), policy);
    refunds.insert(refund);
    Refund stored = refunds.insertAllocations(refund, policy, shares, now);
    Order order = orders.recordRefundEvent(refund.orderId(), CREATED, details(stored), now);
    return new Created(stored, order);
  }

  /** A refund just created, with its shares, and its order after the refund's event. */
  public record Created(Refund refund, Order order) {}

  /**
   * Splits a leased refund that has none yet, under its order's lock: one an older binary created.
   * Empty, writing nothing, when the lease was lost.
   */
  @Transactional
  public Optional<Refund> allocate(
      Refund refund, Order order, UUID leaseToken, AllocationPolicy policy, Instant now) {
    // Lock the order the way a new refund does, so the tenders' balances can't move under us.
    orderRows.lockForRefund(order.merchantId(), order.id());
    List<AllocationPolicy.Share> shares = split(refund.orderId(), refund.amountMinor(), policy);
    return refunds.allocateLeased(refund.id(), leaseToken, policy, shares, now);
  }

  /**
   * Records one tender's share of a leased refund as settled. False, writing nothing, when the
   * lease was lost.
   */
  @Transactional
  public boolean settleShare(
      Refund refund, UUID leaseToken, RefundOutcome.ShareSettled share, Instant now) {
    return refunds.settleShare(
        refund.id(),
        leaseToken,
        share.paymentId(),
        share.providerRefundId(),
        share.code(),
        share.message(),
        now);
  }

  /**
   * Records {@code refund} as made in full: its {@code refund.succeeded} event, then what each
   * tender returned and the order's refunded total, state and event. Empty, writing nothing, when
   * the lease was lost.
   */
  @Transactional
  public Optional<Refund> succeed(
      Refund refund, UUID leaseToken, String providerRefundId, Instant now) {
    Optional<Refund> after =
        refunds.succeed(refund.id(), leaseToken, providerRefundId, refund.amountMinor(), now);
    after.ifPresent(
        r -> {
          orders.recordRefundEvent(r.orderId(), SUCCEEDED, details(r), now);
          orders.recordRefund(r.orderId(), returnedByTender(r), now);
        });
    return after;
  }

  /**
   * Records {@code refund} as partly made: its {@code refund.failed} event saying how much came
   * back, then what the tenders that returned their share gave and the order's refunded total,
   * state and event. Empty, writing nothing, when the lease was lost.
   */
  @Transactional
  public Optional<Refund> partiallyFail(
      Refund refund, UUID leaseToken, RefundOutcome.PartiallyFailed outcome, Instant now) {
    Optional<Refund> after =
        refunds.partiallyFail(
            refund.id(),
            leaseToken,
            outcome.providerRefundId(),
            outcome.returnedMinor(),
            outcome.code(),
            outcome.message(),
            now);
    after.ifPresent(
        r -> {
          orders.recordRefundEvent(r.orderId(), FAILED, details(r), now);
          orders.recordRefund(r.orderId(), returnedByTender(r), now);
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

  /**
   * What each tender returned of a settled refund, by payment id. The worker splits every refund
   * before its first attempt, so a refund without shares can't settle here.
   */
  private static Map<UUID, Long> returnedByTender(Refund r) {
    if (r.allocations().isEmpty()) {
      throw new IllegalStateException("refund " + r.id() + " settled without shares");
    }
    Map<UUID, Long> out = new LinkedHashMap<>();
    for (RefundAllocation a : r.allocations()) {
      if (a.state() == RefundState.SUCCEEDED) {
        out.merge(a.paymentId(), a.amountMinor(), Long::sum);
      }
    }
    return out;
  }

  /** What the refund's events say about it. */
  static OutboxWriter.RefundDetails details(Refund r) {
    return new OutboxWriter.RefundDetails(
        r.id(),
        r.amountMinor(),
        r.lines(),
        r.state().name(),
        r.failureCode(),
        r.state() == RefundState.PARTIALLY_FAILED ? r.returnedMinor() : null);
  }
}
