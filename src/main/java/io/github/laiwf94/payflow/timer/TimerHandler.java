package io.github.laiwf94.payflow.timer;

/**
 * What happens when a timer of one {@link #kind()} is due. A Spring bean implementing this is
 * picked up by {@link TimerHandlers}.
 *
 * <p>{@link #fire} runs inside the transaction that deletes the timer, so its writes commit if and
 * only if the timer is gone: it fires once or not at all, whatever crashes in between. It must
 * therefore only write to the database (making an order due, say), never call another system; work
 * that does belongs to the saga, which the handler can wake. It may schedule another timer,
 * including one of its own kind for the same subject. A handler that throws rolls everything back
 * and the timer is retried with backoff, without end, so a handler must not throw for something
 * that will never change: if the subject has moved on, it returns and the timer is spent.
 */
public interface TimerHandler {

  /** The name timers are scheduled under, e.g. {@code order.reservation_expiry}. */
  String kind();

  void fire(Timer timer);
}
