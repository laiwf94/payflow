package io.github.laiwf94.payflow.timer;

import io.github.laiwf94.payflow.saga.Backoff;
import io.github.laiwf94.payflow.saga.CrashPoints;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer.Sample;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.SmartLifecycle;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Fires due timers. A dispatcher thread leases due timers of the kinds this instance has handlers
 * for, earliest first, and hands each to a virtual thread, at most {@link
 * TimerProperties#concurrency()} at once. Firing is one transaction: delete the timer, fenced on
 * the lease token, then run its handler. The handler's writes and the timer's removal commit
 * together, so a timer fires once or not at all, across crashes, restarts and any number of
 * instances.
 *
 * <ul>
 *   <li>A handler that throws rolls its transaction back; the timer is then retried with full
 *       jitter backoff ({@link Backoff}) and its error recorded, without end. Retries past {@link
 *       #ALERT_ATTEMPTS} are logged as errors.
 *   <li>A worker that dies holding a timer leaves it leased until {@link TimerProperties#lease()}
 *       runs out; the next claim is told it was {@code recovered}. The fire it was in the middle of
 *       rolled back with the connection, so nothing happened twice.
 *   <li>A clean stop stops claiming, gives timers it claimed but hasn't started back at once, waits
 *       {@link TimerProperties#shutdownGrace()} for fires in flight, then hands back what is left
 *       already expired.
 * </ul>
 */
public class TimerWorker implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(TimerWorker.class);

  /** Retries past this many attempts are logged as errors: someone should look. */
  static final int ALERT_ATTEMPTS = 10;

  /** What happened to one claim. */
  enum Result {
    FIRED,
    RETRYING,
    /** The lease was no longer this worker's: rescheduled, cancelled or taken over. */
    LOST,
    HANDED_BACK
  }

  private record Key(String kind, UUID subject) {}

  private final TimerRepository timers;
  private final TimerHandlers handlers;
  private final TimerProperties props;
  private final TimerSignal signal;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final Backoff backoff;
  private final CrashPoints crash;
  private final MeterRegistry meters;

  /** Claims not yet finished, queued or firing: at most one poll's batch. */
  private final Semaphore claimable;

  /** Fires running at once. */
  private final Semaphore firing;

  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

  /** Timers being fired right now, with the lease token each holds. */
  private final Map<Key, UUID> inFlight = new ConcurrentHashMap<>();

  private volatile boolean running;
  private volatile boolean draining;
  private volatile boolean full;
  private Thread dispatcher;

  public TimerWorker(
      TimerRepository timers,
      TimerHandlers handlers,
      TimerProperties props,
      TimerSignal signal,
      PlatformTransactionManager txManager,
      Clock clock,
      Backoff backoff,
      CrashPoints crash,
      MeterRegistry meters) {
    this.timers = timers;
    this.handlers = handlers;
    this.props = props;
    this.signal = signal;
    this.tx = new TransactionTemplate(txManager);
    this.clock = clock;
    this.backoff = backoff;
    this.crash = crash;
    this.meters = meters;
    this.claimable = new Semaphore(props.batchSize());
    this.firing = new Semaphore(props.concurrency());
  }

  /** Wakes the dispatcher instead of waiting for its next poll. */
  public void wakeUp() {
    signal.wakeUp();
  }

  /** Claims one batch and fires it on the calling thread. Returns the number claimed. */
  public int runOnce() {
    List<TimerClaim> claims =
        timers.claimDue(handlers.kinds(), clock.instant(), props.lease(), props.batchSize());
    claims.forEach(this::process);
    return claims.size();
  }

  Result process(TimerClaim claim) {
    Timer timer = claim.timer();
    Key key = new Key(timer.kind(), timer.subjectId());
    MDC.put("timer_kind", timer.kind());
    MDC.put("timer_subject", timer.subjectId().toString());
    inFlight.put(key, claim.leaseToken());
    try {
      if (draining) {
        handBackUnstarted(claim);
        return Result.HANDED_BACK;
      }
      if (claim.recovered()) {
        log.info("firing a timer whose previous lease was not released");
      }
      TimerHandler handler = handlers.get(timer.kind()).orElseThrow();
      Sample sample = io.micrometer.core.instrument.Timer.start(meters);
      Optional<Timer> fired;
      try {
        fired =
            tx.execute(
                status -> {
                  Optional<Timer> taken =
                      timers.take(timer.kind(), timer.subjectId(), claim.leaseToken());
                  if (taken.isPresent()) {
                    crash.at("before:TIMER");
                    handler.fire(taken.get());
                    crash.at("after:TIMER");
                  }
                  return taken;
                });
      } catch (RuntimeException e) {
        sample.stop(meters.timer("payflow.timers.fire", "kind", timer.kind(), "outcome", "failed"));
        return retry(claim, e);
      }
      crash.at("committed:TIMER");
      if (fired == null || fired.isEmpty()) {
        log.info("timer was rescheduled, cancelled or taken over before it fired");
        meters.counter("payflow.timers.lost", "kind", timer.kind()).increment();
        return Result.LOST;
      }
      sample.stop(meters.timer("payflow.timers.fire", "kind", timer.kind(), "outcome", "fired"));
      Duration late = Duration.between(timer.dueAt(), clock.instant());
      meters
          .timer("payflow.timers.lateness", "kind", timer.kind())
          .record(late.isNegative() ? Duration.ZERO : late);
      return Result.FIRED;
    } finally {
      inFlight.remove(key);
      MDC.remove("timer_kind");
      MDC.remove("timer_subject");
    }
  }

  private Result retry(TimerClaim claim, RuntimeException e) {
    Timer timer = claim.timer();
    int next = timer.attempt() + 1;
    Instant now = clock.instant();
    Duration delay = backoff.delay(timer.attempt());
    String error =
        e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
    if (!timers.retry(
        timer.kind(), timer.subjectId(), claim.leaseToken(), next, now.plus(delay), error, now)) {
      log.warn("timer handler failed and the lease was lost meanwhile", e);
      return Result.LOST;
    }
    meters.counter("payflow.timers.retries", "kind", timer.kind()).increment();
    if (next >= ALERT_ATTEMPTS) {
      log.error("timer still not fired after {} attempts", next, e);
    } else {
      log.warn("timer handler failed; retrying in {} ms: {}", delay.toMillis(), error);
    }
    return Result.RETRYING;
  }

  private void dispatch() {
    while (running) {
      try {
        int free = claimable.availablePermits();
        List<TimerClaim> claims =
            free == 0
                ? List.of()
                : timers.claimDue(handlers.kinds(), clock.instant(), props.lease(), free);
        claims.forEach(this::submit);
        if (claims.size() < free) {
          signal.await(props.pollInterval(), () -> running);
        } else if (claimable.availablePermits() == 0) {
          // Every claim is still queued or firing; the next to finish wakes us.
          full = true;
          signal.await(props.pollInterval(), () -> running);
          full = false;
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (RuntimeException e) {
        // Usually the database is unreachable. Keep polling; timers wait in Postgres.
        log.error("timer dispatcher poll failed", e);
        try {
          signal.await(props.pollInterval(), () -> running);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    }
  }

  /**
   * Queues a claim on its own virtual thread, which fires it once one of the {@link
   * TimerProperties#concurrency()} permits is free. Claims a poll took wait their turn under their
   * lease instead of being claimed a few at a time.
   */
  private void submit(TimerClaim c) {
    // Never blocks: the dispatcher is the only acquirer and claimed no more than were free.
    claimable.acquireUninterruptibly();
    try {
      executor.execute(
          () -> {
            boolean permitted = false;
            try {
              permitted = awaitFirePermit();
              if (permitted) {
                process(c);
              } else {
                handBackUnstarted(c);
              }
            } catch (InterruptedException e) {
              // Hand back first: a virtual thread that is interrupted can't use its socket.
              handBackUnstarted(c);
              Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
              log.error("timer processing failed", e);
            } finally {
              if (permitted) {
                firing.release();
              }
              claimable.release();
              if (full) {
                signal.wakeUp();
              }
            }
          });
    } catch (RejectedExecutionException e) {
      claimable.release();
      handBackUnstarted(c);
    }
  }

  /**
   * Waits for a fire permit. False when the worker started stopping meanwhile: a queued claim is
   * then given back at once rather than after the fires ahead of it.
   */
  private boolean awaitFirePermit() throws InterruptedException {
    while (!firing.tryAcquire(20, TimeUnit.MILLISECONDS)) {
      if (draining) {
        return false;
      }
    }
    if (draining) {
      firing.release();
      return false;
    }
    return true;
  }

  private void handBackUnstarted(TimerClaim c) {
    try {
      if (timers.release(
          c.timer().kind(), c.timer().subjectId(), c.leaseToken(), clock.instant())) {
        meters.counter("payflow.timers.handed_back", "how", "unstarted").increment();
      }
    } catch (RuntimeException e) {
      log.error("could not hand back an unstarted timer; its lease will run out", e);
    }
  }

  /** Starts the dispatcher, unless disabled or there is no handler to fire anything with. */
  @Override
  public void start() {
    if (!props.enabled() || running) {
      return;
    }
    if (handlers.isEmpty()) {
      log.info("no timer handlers; the timer worker stays idle");
      return;
    }
    running = true;
    draining = false;
    dispatcher = Thread.ofPlatform().daemon().name("timer-dispatcher").start(this::dispatch);
  }

  /**
   * Stops claiming, gives back claims not yet started, lets fires in flight finish for up to {@link
   * TimerProperties#shutdownGrace()}, then hands back (fences and expires) the rest, so the next
   * instance claims them on its first poll.
   */
  @Override
  public void stop() {
    long started = System.nanoTime();
    long deadline = started + props.shutdownGrace().toNanos();
    running = false;
    draining = true;
    wakeUp();
    int abandoned = 0;
    try {
      if (dispatcher != null) {
        dispatcher.join(Math.max(1, remainingMillis(deadline)));
        if (dispatcher.isAlive()) {
          dispatcher.interrupt();
          dispatcher.join(1_000);
        }
      }
      executor.shutdown();
      if (!executor.awaitTermination(
          Math.max(0, remainingMillis(deadline)), TimeUnit.MILLISECONDS)) {
        executor.shutdownNow();
        executor.awaitTermination(1, TimeUnit.SECONDS);
        for (Map.Entry<Key, UUID> e : Map.copyOf(inFlight).entrySet()) {
          if (abandon(e.getKey(), e.getValue())) {
            abandoned++;
          }
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    log.info(
        "timer worker stopped in {} ms; timers handed back mid-fire: {}",
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
        abandoned);
  }

  /** Stops on its own thread, so the grace runs alongside the other workers', not after them. */
  @Override
  public void stop(Runnable callback) {
    Thread.ofPlatform()
        .name("timer-worker-stop")
        .start(
            () -> {
              try {
                stop();
              } finally {
                callback.run();
              }
            });
  }

  private boolean abandon(Key key, UUID token) {
    try {
      if (timers.abandon(key.kind(), key.subject(), token)) {
        log.warn("timer {} {} still firing at shutdown; handed back", key.kind(), key.subject());
        meters.counter("payflow.timers.handed_back", "how", "mid_fire").increment();
        return true;
      }
    } catch (RuntimeException e) {
      log.error("could not hand back timer {} {} at shutdown", key.kind(), key.subject(), e);
    }
    return false;
  }

  private static long remainingMillis(long deadlineNanos) {
    return TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  /** With the other workers: after the web server, so whatever a handler wakes is reachable. */
  @Override
  public int getPhase() {
    return Integer.MAX_VALUE;
  }
}
