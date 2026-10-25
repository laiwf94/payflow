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

  private final OrderRepository repo;
  private final OutboxWriter outbox;

  public OrderTransitions(OrderRepository repo, OutboxWriter outbox) {
    this.repo = repo;
    this.outbox = outbox;
  }

  /** Stores a new order and its {@code order.created} event. */
  @Transactional
  public Order create(Order order) {
    repo.insert(order);
    outbox.write("order.created", order, null, order.createdAt());
    return order;
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
    after.ifPresent(o -> outbox.write("order.cancel_requested", o, null, now));
    return after;
  }

  /** Starts a full refund. Empty when the order doesn't exist for this merchant or isn't done. */
  @Transactional
  public Optional<Order> requestRefund(String merchantId, UUID id, Instant now) {
    Optional<Order> after = repo.requestRefund(merchantId, id, now);
    after.ifPresent(o -> outbox.write(eventType(o.state()), o, OrderState.COMPLETED, now));
    return after;
  }

  static String eventType(OrderState state) {
    return "order." + state.name().toLowerCase(Locale.ROOT);
  }
}
