package io.github.laiwf94.payflow.hold;

import io.github.laiwf94.payflow.order.CancelReason;
import io.github.laiwf94.payflow.order.Order;
import io.github.laiwf94.payflow.order.OrderState;
import io.github.laiwf94.payflow.timer.TimerService;
import java.time.Instant;
import java.util.UUID;

/**
 * Keeps an order's expiry timers in step with its state, in the transaction that changes it, so a
 * timer exists exactly while the order it would cancel is still unpaid.
 *
 * <ul>
 *   <li>Creating an order schedules {@link #PAYMENT_EXPIRY} for the end of the payment window.
 *   <li>Reserving its stock schedules {@link #RESERVATION_EXPIRY} for the end of the reservation
 *       window.
 *   <li>Leaving the unpaid states, by capturing or by starting to compensate, cancels both.
 * </ul>
 *
 * A timer that fires after the order moved on finds nothing to do ({@link ExpiryHandler}), so the
 * cancels are housekeeping, keeping the timers table to the orders that are actually waiting.
 */
public class OrderHolds {

  /** Releases the stock of an order that held it for the reservation window without paying. */
  public static final String RESERVATION_EXPIRY = "order.reservation_expiry";

  /** Cancels an order that was not paid within the payment window. */
  public static final String PAYMENT_EXPIRY = "order.payment_expiry";

  private final TimerService timers;
  private final HoldProperties props;

  public OrderHolds(TimerService timers, HoldProperties props) {
    this.timers = timers;
    this.props = props;
  }

  /** In the transaction that stores {@code order}. */
  public void created(Order order) {
    timers.schedule(PAYMENT_EXPIRY, order.id(), order.createdAt().plus(props.paymentWindow()));
  }

  /**
   * In the transaction that moves the order from {@code from} to {@code to}, before the order row
   * is written: cancels the timers the move makes pointless. It goes first so that this
   * transaction, like a timer's fire, locks the timer before the order. A fire deletes its timer
   * and then updates the order; doing it the other way round here could deadlock with one.
   */
  public void beforeMove(UUID orderId, OrderState from, OrderState to) {
    if (unpaid(from) && !unpaid(to)) {
      timers.cancel(PAYMENT_EXPIRY, orderId);
      if (from != OrderState.PENDING) {
        timers.cancel(RESERVATION_EXPIRY, orderId);
      }
    }
  }

  /** In the same transaction, once the order row says it moved: schedules what the move starts. */
  public void afterMove(Order after, Instant now) {
    if (after.state() == OrderState.INVENTORY_RESERVED) {
      timers.schedule(RESERVATION_EXPIRY, after.id(), now.plus(props.reservationWindow()));
    }
  }

  /** Before capture: the states a payment expiry can cancel an order from. */
  static boolean unpaid(OrderState s) {
    return CancelReason.PAYMENT_EXPIRED.appliesTo().contains(s);
  }
}
