package io.github.laiwf94.payflow.timer;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Schedules, moves and cancels timers. Every change is made in the caller's transaction and only
 * there, so a timer exists exactly when the state that needs it was committed: an order that holds
 * stock and the timer that will release it commit together or not at all. Calling any of these
 * without a transaction is a bug and throws.
 *
 * <p>A subject has at most one timer of each kind. A timer that fired is gone, so scheduling the
 * same kind for the same subject afterwards makes a new one.
 */
public class TimerService {

  private final TimerRepository timers;
  private final TimerHandlers handlers;
  private final TimerSignal signal;
  private final Clock clock;
  private final Duration wakeWithin;

  /**
   * @param wakeWithin a schedule due within this long of now wakes the worker when it commits,
   *     rather than leaving it to the next poll; the worker's poll interval
   */
  public TimerService(
      TimerRepository timers,
      TimerHandlers handlers,
      TimerSignal signal,
      Clock clock,
      Duration wakeWithin) {
    this.timers = timers;
    this.handlers = handlers;
    this.signal = signal;
    this.clock = clock;
    this.wakeWithin = wakeWithin;
  }

  /**
   * Adds a timer for {@code subject} due at {@code dueAt}, unless it already has one of this kind,
   * which is left as it is. True when a timer was added. A time in the past fires as soon as a
   * worker gets to it.
   */
  public boolean schedule(String kind, UUID subject, Instant dueAt) {
    check(kind, subject, dueAt);
    boolean added = timers.insertIfAbsent(kind, subject, dueAt, clock.instant());
    if (added) {
      wakeAfterCommit(dueAt);
    }
    return added;
  }

  /**
   * Makes the subject's timer of this kind due at {@code dueAt}, adding it if there is none. A fire
   * of the old one that a worker has claimed but not committed does nothing; one that committed
   * first already removed it, and this adds a new one.
   */
  public void reschedule(String kind, UUID subject, Instant dueAt) {
    check(kind, subject, dueAt);
    timers.upsert(kind, subject, dueAt, clock.instant());
    wakeAfterCommit(dueAt);
  }

  /**
   * Removes the subject's timer of this kind. False when there was none: it never existed, was
   * cancelled, or already fired. A fire that a worker has claimed but not committed does nothing; a
   * fire that is committing holds the row, so this waits for it and then finds nothing.
   */
  public boolean cancel(String kind, UUID subject) {
    requireTransaction();
    handlers.require(Objects.requireNonNull(kind, "kind"));
    return timers.delete(kind, Objects.requireNonNull(subject, "subject"));
  }

  /** The subject's pending timer of this kind, if it has one. Needs no transaction. */
  public Optional<Timer> find(String kind, UUID subject) {
    return timers.find(kind, subject);
  }

  private void check(String kind, UUID subject, Instant dueAt) {
    requireTransaction();
    handlers.require(Objects.requireNonNull(kind, "kind"));
    Objects.requireNonNull(subject, "subject");
    Objects.requireNonNull(dueAt, "dueAt");
  }

  private static void requireTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalTransactionStateException(
          "timers are changed in the caller's transaction, and there is none");
    }
  }

  private void wakeAfterCommit(Instant dueAt) {
    if (dueAt.isAfter(clock.instant().plus(wakeWithin))
        || !TransactionSynchronizationManager.isSynchronizationActive()) {
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            signal.wakeUp();
          }
        });
  }
}
