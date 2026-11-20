package io.github.laiwf94.payflow.hold;

import io.github.laiwf94.payflow.order.OrderRepository;
import io.github.laiwf94.payflow.saga.SagaWorker;
import io.github.laiwf94.payflow.timer.Timer;
import io.github.laiwf94.payflow.timer.TimerHandler;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Fires {@link HoldProperties#reauthorizeBefore()} before a waiting manual-capture order's
 * authorization lapses: it flags the order for re-authorization and makes it due now, in the
 * timer's transaction, and wakes the saga once that commits. The saga's capture step then extends
 * the authorization before going back to waiting for the merchant.
 *
 * <p>Only the database is touched, as a {@link TimerHandler} must. An order that was captured,
 * cancelled, asked to capture, or already asked to re-authorize is left alone and the timer is
 * spent: the capture or the reversal is already on its way, and an authorization is extended at
 * most once.
 */
public class ReauthorizationHandler implements TimerHandler {

  private static final Logger log = LoggerFactory.getLogger(ReauthorizationHandler.class);

  private final OrderRepository orders;
  private final ObjectProvider<SagaWorker> saga;
  private final Clock clock;
  private final MeterRegistry meters;

  /**
   * @param saga woken once the request commits; looked up lazily because the saga's transitions
   *     schedule timers, which need this handler
   */
  public ReauthorizationHandler(
      OrderRepository orders, ObjectProvider<SagaWorker> saga, Clock clock, MeterRegistry meters) {
    this.orders = orders;
    this.saga = saga;
    this.clock = clock;
    this.meters = meters;
  }

  @Override
  public String kind() {
    return OrderHolds.REAUTHORIZATION;
  }

  @Override
  public void fire(Timer timer) {
    boolean requested =
        orders.requestReauthorization(timer.subjectId(), clock.instant()).isPresent();
    meters
        .counter("payflow.holds.reauthorization", "outcome", requested ? "requested" : "moved_on")
        .increment();
    if (!requested) {
      return;
    }
    log.info("order {} asked to re-authorize before its authorization lapses", timer.subjectId());
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            saga.ifAvailable(SagaWorker::wakeUp);
          }
        });
  }
}
