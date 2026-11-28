package io.github.laiwf94.payflow.order;

import io.github.laiwf94.payflow.hold.OrderHolds;
import io.github.laiwf94.payflow.money.Money;
import io.github.laiwf94.payflow.outbox.OutboxWriter;
import io.github.laiwf94.payflow.payment.Payment;
import io.github.laiwf94.payflow.payment.PaymentRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

/**
 * Every change to an order goes through here, and every state change writes its event to the outbox
 * in the same transaction (the transactional outbox pattern): the event exists if and only if the
 * change committed.
 */
@Component
public class OrderTransitions {

  /**
   * The type of the event a new order writes; each later state has its own ({@link #eventType}).
   */
  public static final String CREATED = "order.created";

  /** The type of the event a cancel request writes before the saga acts on it. */
  public static final String CANCEL_REQUESTED = "order.cancel_requested";

  /** The type of the event a manual-capture order's capture request writes. */
  public static final String CAPTURE_REQUESTED = "order.capture_requested";

  /**
   * The type of the event written when a waiting manual-capture order's authorization was extended.
   * The order stays in {@code PAYMENT_AUTHORIZED}; the event carries the new expiry.
   */
  public static final String PAYMENT_REAUTHORIZED = "order.payment_reauthorized";

  private final OrderRepository repo;
  private final OutboxWriter outbox;
  private final OrderHolds holds;
  private final PaymentRepository payments;

  public OrderTransitions(
      OrderRepository repo, OutboxWriter outbox, OrderHolds holds, PaymentRepository payments) {
    this.repo = repo;
    this.outbox = outbox;
    this.holds = holds;
    this.payments = payments;
  }

  /** A tender of a new order: {@code amount} of it paid with {@code method}. */
  public record Tender(String method, Money amount) {}

  /**
   * Stores a new order paid with one tender, its {@code paymentMethod}, for the whole amount: see
   * {@link #create(Order, List)}.
   */
  @Transactional
  public boolean create(Order order) {
    return create(order, List.of(new Tender(order.paymentMethod(), order.amount())));
  }

  /**
   * Stores a new order, one payment row per tender (authorized in the order given), its {@code
   * order.created} event and the timer that cancels it if it is not paid in time. Returns false,
   * writing nothing, when the merchant already has an order with the same reference.
   *
   * @throws IllegalArgumentException when there are no tenders, more than 16, or their amounts are
   *     not in the order's currency or don't add up to its amount
   */
  @Transactional
  public boolean create(Order order, List<Tender> tenders) {
    if (tenders.isEmpty() || tenders.size() > 16) {
      throw new IllegalArgumentException("an order has 1 to 16 tenders, not " + tenders.size());
    }
    Money sum = null;
    for (Tender t : tenders) {
      sum = sum == null ? t.amount() : sum.plus(t.amount());
    }
    if (!order.amount().equals(sum)) {
      throw new IllegalArgumentException(
          "tenders add up to " + sum + ", not the order's " + order.amount());
    }
    if (!repo.insert(order)) {
      return false;
    }
    if (tenders.size() > 1) {
      List<Payment> rows = new ArrayList<>(tenders.size());
      for (int i = 0; i < tenders.size(); i++) {
        Tender t = tenders.get(i);
        rows.add(
            Payment.create(
                UUID.randomUUID(), order.id(), i + 1, t.method(), t.amount(), order.createdAt()));
      }
      payments.split(rows);
    }
    outbox.write(CREATED, order, null, order.createdAt());
    holds.created(order);
    return true;
  }

  /**
   * Applies {@code change} to {@code before} if the caller still holds the lease. Returns the
   * updated order, or empty when the write was fenced out.
   */
  @Transactional
  public Optional<Order> apply(Order before, UUID leaseToken, StateChange change, Instant now) {
    return apply(before, leaseToken, change, List.of(), now);
  }

  /**
   * {@link #apply(Order, UUID, StateChange, Instant)}, also recording what the step learned about
   * {@code tenders}, the order's payment rows, in the same transaction. The order's write is the
   * fence: when it is refused, no tender is written.
   */
  @Transactional
  public Optional<Order> apply(
      Order before, UUID leaseToken, StateChange change, List<Payment> tenders, Instant now) {
    boolean moves = change.state() != before.state();
    if (moves) {
      holds.beforeMove(before, change.state());
    }
    Optional<Order> after =
        repo.update(before.id(), leaseToken, before.state(), before.version(), change, now);
    if (after.isEmpty()) {
      if (moves) {
        // The timers cancelled above belong to the worker that fenced us out.
        TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
      }
      return after;
    }
    payments.save(before.id(), tenders, now);
    if (after.get().state() == OrderState.FAILED || after.get().state() == OrderState.CANCELLED) {
      // A tender still pending was never tried: the order failed before reaching it.
      payments.cancelPending(before.id(), now);
    }
    if (moves) {
      outbox.write(eventType(after.get().state()), after.get(), before.state(), now);
      holds.afterMove(after.get(), now);
    } else if (before.reauthorizedAt() == null && after.get().reauthorizedAt() != null) {
      outbox.write(PAYMENT_REAUTHORIZED, after.get(), null, now);
    }
    return after;
  }

  /** Flags a cancel. Empty when the order doesn't exist for this merchant or is past capture. */
  @Transactional
  public Optional<Order> requestCancel(String merchantId, UUID id, Instant now) {
    Optional<Order> after = repo.requestCancel(merchantId, id, now);
    after.ifPresent(o -> outbox.write(CANCEL_REQUESTED, o, null, now));
    return after;
  }

  /**
   * Records a capture request on a manual-capture order. Empty when the order doesn't exist for
   * this merchant or can't be captured now (see {@link OrderRepository#requestCapture}).
   */
  @Transactional
  public Optional<Order> requestCapture(String merchantId, UUID id, Instant now) {
    Optional<Order> after = repo.requestCapture(merchantId, id, now);
    after.ifPresent(o -> outbox.write(CAPTURE_REQUESTED, o, null, now));
    return after;
  }

  /**
   * Records what a settled refund returned on its order and on each tender that returned part of it
   * ({@code byTender}, payment id to amount), and writes the order's event, in the caller's
   * transaction (the refund row changes in the same one). The order's refunded total grows by the
   * shares' sum, so it stays the sum of its tenders'.
   *
   * @throws IllegalStateException when the order is not refundable, or a share names a payment that
   *     isn't one of its tenders; the database refuses a refund row for such an order, so this
   *     means the two disagree and the transaction must not commit
   */
  @Transactional
  public Order recordRefund(UUID orderId, Map<UUID, Long> byTender, Instant now) {
    long amount = byTender.values().stream().mapToLong(Long::longValue).sum();
    if (amount < 1) {
      throw new IllegalArgumentException("a refund returns something: " + byTender);
    }
    Order after =
        repo.addRefunded(orderId, amount, now)
            .orElseThrow(
                () -> new IllegalStateException("order " + orderId + " is not refundable"));
    byTender.forEach(
        (payment, share) -> {
          if (!payments.addRefunded(orderId, payment, share, now)) {
            throw new IllegalStateException(
                "order " + orderId + " has no tender " + payment + " to refund");
          }
        });
    OrderState before =
        after.refundedMinor() == amount ? OrderState.COMPLETED : OrderState.PARTIALLY_REFUNDED;
    outbox.write(eventType(after.state()), after, before, now);
    return after;
  }

  /**
   * Writes an event about one of the order's refunds ({@code refund.created}, {@code
   * refund.succeeded} or {@code refund.failed}), in the caller's transaction, under a new version
   * of the order so it has its own place in the order's stream. Returns the order as it now is.
   *
   * @throws IllegalStateException when the order doesn't exist; a refund row can't exist without
   *     its order, so the transaction must not commit
   */
  @Transactional
  public Order recordRefundEvent(
      UUID orderId, String type, OutboxWriter.RefundDetails refund, Instant now) {
    Order after =
        repo.touch(orderId, now)
            .orElseThrow(() -> new IllegalStateException("order " + orderId + " does not exist"));
    outbox.writeRefund(type, after, refund, now);
    return after;
  }

  public static String eventType(OrderState state) {
    return "order." + state.name().toLowerCase(Locale.ROOT);
  }
}
