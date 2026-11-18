package io.github.laiwf94.payflow.hold;

import io.github.laiwf94.payflow.order.CancelReason;
import io.github.laiwf94.payflow.order.Order;
import io.github.laiwf94.payflow.order.OrderRepository;
import io.github.laiwf94.payflow.order.OrderTransitions;
import io.github.laiwf94.payflow.outbox.OutboxWriter;
import io.github.laiwf94.payflow.saga.SagaWorker;
import io.github.laiwf94.payflow.timer.Timer;
import io.github.laiwf94.payflow.timer.TimerHandler;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Cancels an order whose hold ran out, the way a merchant's cancel does: it flags the order with
 * its {@link CancelReason}, makes it due now and writes {@code order.cancel_requested}, all in the
 * timer's transaction. The saga then compensates at its next decision point, through {@code
 * REVERSING_PAYMENT} if a payment may exist and {@code RELEASING_INVENTORY}, to {@code CANCELLED}.
 *
 * <p>Only the database is touched, as a {@link TimerHandler} must. An order that has moved past the
 * states the reason applies to, or that something already asked to cancel, is left alone and the
 * timer is spent.
 */
public class ExpiryHandler implements TimerHandler {

  private static final Logger log = LoggerFactory.getLogger(ExpiryHandler.class);

  private final String kind;
  private final CancelReason reason;
  private final OrderRepository orders;
  private final OutboxWriter outbox;
  private final ObjectProvider<SagaWorker> saga;
  private final Clock clock;
  private final MeterRegistry meters;

  /**
   * @param saga woken once an expiry commits; looked up lazily because the saga's transitions
   *     schedule timers, which need this handler
   */
  public ExpiryHandler(
      String kind,
      CancelReason reason,
      OrderRepository orders,
      OutboxWriter outbox,
      ObjectProvider<SagaWorker> saga,
      Clock clock,
      MeterRegistry meters) {
    if (reason == CancelReason.REQUESTED) {
      throw new IllegalArgumentException("a timer can't request a cancel on the merchant's behalf");
    }
    this.kind = kind;
    this.reason = reason;
    this.orders = orders;
    this.outbox = outbox;
    this.saga = saga;
    this.clock = clock;
    this.meters = meters;
  }

  @Override
  public String kind() {
    return kind;
  }

  @Override
  public void fire(Timer timer) {
    Instant now = clock.instant();
    Optional<Order> expired = orders.expire(timer.subjectId(), reason, now);
    if (expired.isEmpty()) {
      meters
          .counter("payflow.holds.expired", "reason", reason.code(), "outcome", "moved_on")
          .increment();
      return;
    }
    Order o = expired.get();
    outbox.write(OrderTransitions.CANCEL_REQUESTED, o, null, now);
    meters
        .counter("payflow.holds.expired", "reason", reason.code(), "outcome", "cancelled")
        .increment();
    log.info("order {} in {} is being cancelled: {}", o.id(), o.state(), reason.code());
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            saga.ifAvailable(SagaWorker::wakeUp);
          }
        });
  }
}
