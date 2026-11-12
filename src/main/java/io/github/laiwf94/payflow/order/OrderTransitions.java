package io.github.laiwf94.payflow.order;

import io.github.laiwf94.payflow.outbox.OutboxWriter;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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

  private final OrderRepository repo;
  private final OutboxWriter outbox;

  public OrderTransitions(OrderRepository repo, OutboxWriter outbox) {
    this.repo = repo;
    this.outbox = outbox;
  }

  /**
   * Stores a new order and its {@code order.created} event. Returns false, writing nothing, when
   * the merchant already has an order with the same reference.
   */
  @Transactional
  public boolean create(Order order) {
    if (!repo.insert(order)) {
      return false;
    }
    outbox.write(CREATED, order, null, order.createdAt());
    return true;
  }

  /**
   * Applies {@code change} to {@code before} if the caller still holds the lease. Returns the
   * updated order, or empty when the write was fenced out.
   */
  @Transactional
  public Optional<Order> apply(Order before, UUID leaseToken, StateChange change, Instant now) {
    Optional<Order> after = repo.update(before.id(), leaseToken, before.state(), change, now);
    after
        .filter(o -> o.state() != before.state())
        .ifPresent(o -> outbox.write(eventType(o.state()), o, before.state(), now));
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
   * Records a succeeded refund of {@code amount} on its order and writes the order's event, in the
   * caller's transaction (the refund row changes in the same one).
   *
   * @throws IllegalStateException when the order is not refundable; the database refuses a refund
   *     row for such an order, so this means the two disagree and the transaction must not commit
   */
  @Transactional
  public Order recordRefund(UUID orderId, long amount, Instant now) {
    Order after =
        repo.addRefunded(orderId, amount, now)
            .orElseThrow(
                () -> new IllegalStateException("order " + orderId + " is not refundable"));
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
