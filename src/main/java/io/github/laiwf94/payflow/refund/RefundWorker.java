package io.github.laiwf94.payflow.refund;

import io.github.laiwf94.payflow.order.Order;
import io.github.laiwf94.payflow.order.OrderRepository;
import io.github.laiwf94.payflow.saga.Backoff;
import io.github.laiwf94.payflow.saga.CrashPoints;
import io.github.laiwf94.payflow.saga.SagaProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
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

/**
 * Drives each pending refund to {@code SUCCEEDED} or {@code FAILED}, one refund row at a time and
 * independently of its order, so refunds of one order run side by side and a refund never waits
 * for, or blocks, the order's saga.
 *
 * <p>It works like the saga worker, with the same settings ({@code payflow.saga.*}): a dispatcher
 * thread leases due refunds from Postgres and hands each to a virtual thread; no transaction is
 * open while the provider is asked; the lease token, checked on every write, keeps two workers from
 * settling one refund. A refund is one step, so a claim ends with exactly one write: settled, or
 * scheduled for another try. Refunds are retried until the provider answers, the way a saga's
 * compensation is: once a merchant asked for the money back, giving up would keep it.
 *
 * <p>A worker that dies mid-call leaves the lease to expire, and the next claim is told the refund
 * was {@code recovered}. A clean stop hands back what is still mid-call when {@link
 * SagaProperties#shutdownGrace()} runs out, already expired.
 */
public class RefundWorker implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(RefundWorker.class);

  /** Retries past this many attempts are logged as errors: someone should look. */
  static final int ALERT_ATTEMPTS = 10;

  private final RefundRepository refunds;
  private final OrderRepository orders;
  private final RefundTransitions transitions;
  private final RefundAttempt attempt;
  private final SagaProperties props;
  private final Clock clock;
  private final Backoff backoff;
  private final CrashPoints crash;
  private final MeterRegistry meters;
  private final Semaphore permits;
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
  private final Object signal = new Object();

  /** Refunds being tried right now, by id, with the lease token each holds. */
  private final Map<UUID, UUID> inFlight = new ConcurrentHashMap<>();

  private boolean woken;
  private volatile boolean running;
  private volatile boolean draining;
  private Thread dispatcher;

  public RefundWorker(
      RefundRepository refunds,
      OrderRepository orders,
      RefundTransitions transitions,
      RefundAttempt attempt,
      SagaProperties props,
      Clock clock,
      Backoff backoff,
      CrashPoints crash,
      MeterRegistry meters) {
    this.refunds = refunds;
    this.orders = orders;
    this.transitions = transitions;
    this.attempt = attempt;
    this.props = props;
    this.clock = clock;
    this.backoff = backoff;
    this.crash = crash;
    this.meters = meters;
    // Refunds are rare next to orders; one poll's worth at a time is plenty, and keeps them from
    // crowding the provider while orders are being taken.
    this.permits = new Semaphore(props.batchSize());
  }

  /** Wakes the dispatcher, e.g. right after a refund commits, instead of waiting for a poll. */
  public void wakeUp() {
    synchronized (signal) {
      woken = true;
      signal.notifyAll();
    }
  }

  /** Claims one batch and processes it on the calling thread. Returns the number claimed. */
  public int runOnce() {
    List<RefundClaim> claims = refunds.claimDue(clock.instant(), props.lease(), props.batchSize());
    claims.forEach(this::process);
    return claims.size();
  }

  void process(RefundClaim claim) {
    Refund refund = claim.refund();
    UUID token = claim.leaseToken();
    MDC.put("refund_id", refund.id().toString());
    MDC.put("order_id", refund.orderId().toString());
    inFlight.put(refund.id(), token);
    try {
      if (draining) {
        handBack(claim);
        return;
      }
      if (claim.recovered()) {
        log.info("resuming refund after a lease that was not released");
      }
      Order order = orders.findById(refund.orderId()).orElseThrow();
      crash.at("before:REFUND");
      long started = System.nanoTime();
      RefundOutcome outcome;
      try {
        outcome = attempt.execute(refund, order, claim.recovered() || refund.attempt() > 0);
      } catch (RuntimeException e) {
        log.warn("refund attempt failed unexpectedly", e);
        outcome = new RefundOutcome.Retry("unexpected " + e.getClass().getSimpleName());
      }
      Timer.builder("payflow.refund.attempt")
          .tag("outcome", outcome.getClass().getSimpleName().toLowerCase(Locale.ROOT))
          .register(meters)
          .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
      crash.at("after:REFUND");
      settle(refund, token, outcome);
      crash.at("committed:REFUND");
    } finally {
      inFlight.remove(refund.id());
      MDC.remove("refund_id");
      MDC.remove("order_id");
    }
  }

  private void settle(Refund refund, UUID token, RefundOutcome outcome) {
    Instant now = clock.instant();
    switch (outcome) {
      case RefundOutcome.Succeeded s -> {
        Optional<Refund> done = transitions.succeed(refund, token, s.providerRefundId(), now);
        if (done.isEmpty()) {
          log.warn("lost the lease on a refund; another worker has it");
          return;
        }
        log.info("refund of {} succeeded as {}", refund.amountMinor(), s.providerRefundId());
        meters.counter("payflow.refunds.settled", "state", "succeeded").increment();
      }
      case RefundOutcome.Failed f -> {
        if (transitions.fail(refund, token, f.code(), f.message(), now).isEmpty()) {
          log.warn("lost the lease on a refund; another worker has it");
          return;
        }
        // Nothing went back to the customer; the merchant has to decide what happens next.
        log.error("refund of {} failed: {}", refund.amountMinor(), f.message());
        meters.counter("payflow.refunds.settled", "state", "failed").increment();
      }
      case RefundOutcome.Retry r -> {
        int next = refund.attempt() + 1;
        Duration delay = backoff.delay(refund.attempt());
        if (delay.compareTo(r.minDelay()) < 0) {
          delay = r.minDelay();
        }
        if (!refunds.retry(refund.id(), token, next, now.plus(delay), now)) {
          log.warn("lost the lease on a refund; another worker has it");
          return;
        }
        meters.counter("payflow.refunds.retries").increment();
        if (next >= ALERT_ATTEMPTS) {
          log.error("refund still not made after {} attempts: {}", next, r.reason());
        } else {
          log.info("refund will retry in {} ms: {}", delay.toMillis(), r.reason());
        }
      }
    }
  }

  /** Gives back a claim that lands while stopping; a recovered one stays a recovery. */
  private void handBack(RefundClaim c) {
    UUID id = c.refund().id();
    boolean handed =
        c.recovered() ? refunds.abandon(id, c.leaseToken()) : refunds.release(id, c.leaseToken());
    if (handed) {
      meters.counter("payflow.refunds.handed_back", "how", "unstarted").increment();
    }
  }

  private void dispatch() {
    while (running) {
      try {
        int free = Math.min(permits.availablePermits(), props.batchSize());
        List<RefundClaim> claims =
            free == 0 ? List.of() : refunds.claimDue(clock.instant(), props.lease(), free);
        claims.forEach(this::submit);
        if (claims.size() < free || free == 0) {
          awaitSignal();
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (RuntimeException e) {
        // Usually the database is unreachable. Keep polling; refunds wait in Postgres.
        log.error("refund dispatcher poll failed", e);
        try {
          awaitSignal();
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    }
  }

  private void submit(RefundClaim c) {
    // Never blocks: the dispatcher is the only acquirer and claimed no more than were free.
    permits.acquireUninterruptibly();
    try {
      executor.execute(
          () -> {
            try {
              process(c);
            } catch (RuntimeException e) {
              log.error("refund processing failed", e);
            } finally {
              permits.release();
            }
          });
    } catch (RejectedExecutionException e) {
      permits.release();
      handBack(c);
    }
  }

  private void awaitSignal() throws InterruptedException {
    long deadline = System.nanoTime() + Math.max(1, props.pollInterval().toNanos());
    synchronized (signal) {
      long left;
      while (!woken && running && (left = deadline - System.nanoTime()) > 0) {
        TimeUnit.NANOSECONDS.timedWait(signal, left);
      }
      woken = false;
    }
  }

  @Override
  public void start() {
    if (!props.enabled() || running) {
      return;
    }
    running = true;
    dispatcher = Thread.ofPlatform().daemon().name("refund-dispatcher").start(this::dispatch);
  }

  /**
   * Stops claiming, lets in-flight refunds finish for up to {@link SagaProperties#shutdownGrace()},
   * then abandons (fences and expires) whatever is still asking the provider, so the next instance
   * claims it on its first poll.
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
        for (Map.Entry<UUID, UUID> e : Map.copyOf(inFlight).entrySet()) {
          if (abandon(e.getKey(), e.getValue())) {
            abandoned++;
          }
        }
        executor.shutdownNow();
        if (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
          log.warn("refund attempts still running after they were handed back");
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    log.info(
        "refund worker stopped in {} ms; refunds handed back mid-call: {}",
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
        abandoned);
  }

  /** Stops on its own thread, so the grace runs alongside the saga worker's, not after it. */
  @Override
  public void stop(Runnable callback) {
    Thread.ofPlatform()
        .name("refund-worker-stop")
        .start(
            () -> {
              try {
                stop();
              } finally {
                callback.run();
              }
            });
  }

  private boolean abandon(UUID refundId, UUID token) {
    try {
      if (refunds.abandon(refundId, token)) {
        log.warn("refund {} still mid-call at shutdown; handed back for the next worker", refundId);
        meters.counter("payflow.refunds.handed_back", "how", "mid_call").increment();
        return true;
      }
    } catch (RuntimeException e) {
      log.error("could not hand back refund {} at shutdown", refundId, e);
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

  /** With the saga worker: after the web server, so the in-process simulators are reachable. */
  @Override
  public int getPhase() {
    return Integer.MAX_VALUE;
  }
}
