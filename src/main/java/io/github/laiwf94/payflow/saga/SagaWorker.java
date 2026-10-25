package io.github.laiwf94.payflow.saga;

import io.github.laiwf94.payflow.order.Claim;
import io.github.laiwf94.payflow.order.Order;
import io.github.laiwf94.payflow.order.OrderRepository;
import io.github.laiwf94.payflow.order.OrderState;
import io.github.laiwf94.payflow.order.OrderTransitions;
import io.github.laiwf94.payflow.order.StateChange;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.SmartLifecycle;

/**
 * Drives orders through their saga.
 *
 * <p>A dispatcher thread leases due orders from Postgres and hands each to a virtual thread, which
 * runs steps until the order is terminal, has to wait for a retry, or the lease is lost. No
 * database transaction is open while a step talks to a dependency: the lease token, checked on
 * every write, is what keeps two workers from acting on one order.
 *
 * <p>If the process dies mid-step the lease simply expires. The next worker to claim the order is
 * told the work was {@code resumed}, so the step checks what already happened before acting.
 */
public class SagaWorker implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(SagaWorker.class);

  /** Compensation retries past this many attempts are logged as errors: someone should look. */
  static final int COMPENSATION_ALERT_ATTEMPTS = 10;

  private final OrderRepository repo;
  private final OrderTransitions transitions;
  private final Map<OrderState, SagaStep> steps = new EnumMap<>(OrderState.class);
  private final SagaProperties props;
  private final Clock clock;
  private final Backoff backoff;
  private final CrashPoints crash;
  private final MeterRegistry meters;
  private final Semaphore permits;
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
  private final Object signal = new Object();
  private boolean woken;
  private volatile boolean running;
  private Thread dispatcher;

  public SagaWorker(
      OrderRepository repo,
      OrderTransitions transitions,
      List<SagaStep> stepList,
      SagaProperties props,
      Clock clock,
      Backoff backoff,
      CrashPoints crash,
      MeterRegistry meters) {
    this.repo = repo;
    this.transitions = transitions;
    this.props = props;
    this.clock = clock;
    this.backoff = backoff;
    this.crash = crash;
    this.meters = meters;
    this.permits = new Semaphore(props.maxInFlight());
    for (SagaStep s : stepList) {
      if (steps.put(s.handles(), s) != null) {
        throw new IllegalStateException("two steps handle " + s.handles());
      }
    }
  }

  /** Wakes the dispatcher, e.g. right after a new order commits, instead of waiting for a poll. */
  public void wakeUp() {
    synchronized (signal) {
      woken = true;
      signal.notifyAll();
    }
  }

  /** Claims one batch and processes it on the calling thread. Returns the number claimed. */
  public int runOnce() {
    List<Claim> claims = repo.claimDue(clock.instant(), props.lease(), props.batchSize());
    claims.forEach(this::process);
    return claims.size();
  }

  void process(Claim claim) {
    Order order = claim.order();
    boolean resumed = claim.recovered() || order.attempt() > 0;
    MDC.put("order_id", order.id().toString());
    MDC.put("correlation_id", order.correlationId());
    try {
      if (claim.recovered()) {
        log.info("resuming order in {} after an expired lease", order.state());
      }
      while (order != null && !order.state().isTerminal()) {
        order = step(order, claim.leaseToken(), resumed);
        resumed = false;
      }
    } finally {
      MDC.remove("order_id");
      MDC.remove("correlation_id");
    }
  }

  /** Runs one step. Returns the order to continue with, or null to stop. */
  private Order step(Order order, UUID token, boolean resumed) {
    OrderState state = order.state();
    if (order.cancelRequested() && isForward(state)) {
      return move(order, token, state.compensation(), StepOutcome.Fields.NONE, null, null);
    }
    SagaStep step = steps.get(state);
    StepOutcome outcome;
    if (step == null) {
      log.error("no step handles {}", state);
      outcome = new StepOutcome.Retry("no step handles " + state);
    } else {
      crash.at("before:" + state);
      long started = System.nanoTime();
      try {
        outcome = step.execute(order, resumed);
      } catch (RuntimeException e) {
        log.warn("step {} failed unexpectedly", state, e);
        outcome = new StepOutcome.Retry("unexpected " + e.getClass().getSimpleName());
      }
      Timer.builder("payflow.saga.step")
          .tag("state", state.name())
          .tag("outcome", outcome.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT))
          .register(meters)
          .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
      crash.at("after:" + state);
    }
    return switch (outcome) {
      case StepOutcome.Advance a -> move(order, token, a.next(), a.fields(), null, null);
      case StepOutcome.Fail f ->
          move(order, token, f.compensateTo(), StepOutcome.Fields.NONE, f.code(), f.message());
      case StepOutcome.Retry r -> retry(order, token, r);
    };
  }

  private Order retry(Order order, UUID token, StepOutcome.Retry r) {
    Instant now = clock.instant();
    OrderState state = order.state();
    int next = order.attempt() + 1;
    if (isForward(state)
        && (next >= props.maxAttempts()
            || !now.isBefore(order.stepStartedAt().plus(props.stepTimeout())))) {
      log.warn("{} gave up after {} attempts: {}", state, next, r.reason());
      return move(
          order,
          token,
          state.compensation(),
          StepOutcome.Fields.NONE,
          "step_timeout",
          stepName(state) + " did not succeed in time: " + r.reason());
    }
    Duration delay = backoff.delay(order.attempt());
    if (delay.compareTo(r.minDelay()) < 0) {
      delay = r.minDelay();
    }
    meters.counter("payflow.saga.retries", "state", state.name()).increment();
    if (state.isCompensating() && next >= COMPENSATION_ALERT_ATTEMPTS) {
      log.error("compensation {} still failing after {} attempts: {}", state, next, r.reason());
    } else {
      log.info("{} will retry in {} ms: {}", state, delay.toMillis(), r.reason());
    }
    transitions.apply(
        order,
        token,
        StateChange.to(state).attempt(next).nextAttemptAt(now.plus(delay)).build(),
        now);
    crash.at("committed:" + state);
    return null;
  }

  private Order move(
      Order order,
      UUID token,
      OrderState next,
      StepOutcome.Fields f,
      String failureCode,
      String failureMessage) {
    Instant now = clock.instant();
    StateChange.Builder b = StateChange.to(next).attempt(0).stepStartedAt(now);
    if (!next.isTerminal()) {
      b.nextAttemptAt(now).keepLeaseUntil(now.plus(props.lease()));
    }
    if (f.reservationId() != null) {
      b.reservationId(f.reservationId());
    }
    if (f.paymentId() != null) {
      b.paymentId(f.paymentId());
    }
    if (f.shipmentId() != null) {
      b.shipment(f.shipmentId(), f.trackingNumber());
    }
    if (f.capturedMinor() != null) {
      b.capturedMinor(f.capturedMinor());
    }
    if (f.refundedMinor() != null) {
      b.refundedMinor(f.refundedMinor());
    }
    if (failureCode != null) {
      b.failure(failureCode, failureMessage);
    }
    Optional<Order> updated = transitions.apply(order, token, b.build(), now);
    if (updated.isEmpty()) {
      log.warn("lost the lease on order in {}; another worker has it", order.state());
      return null;
    }
    log.info("order {} -> {}", order.state(), next);
    meters
        .counter("payflow.saga.transitions", "from", order.state().name(), "to", next.name())
        .increment();
    crash.at("committed:" + order.state());
    return updated.get();
  }

  private static boolean isForward(OrderState s) {
    return !s.isTerminal() && !s.isCompensating();
  }

  private static String stepName(OrderState s) {
    return switch (s) {
      case PENDING -> "inventory reservation";
      case INVENTORY_RESERVED -> "payment authorization";
      case PAYMENT_AUTHORIZED -> "payment capture";
      case PAYMENT_CAPTURED -> "shipment";
      default -> s.name().toLowerCase(java.util.Locale.ROOT);
    };
  }

  private void dispatch() {
    while (running) {
      try {
        int free = Math.min(permits.availablePermits(), props.batchSize());
        List<Claim> claims =
            free == 0 ? List.of() : repo.claimDue(clock.instant(), props.lease(), free);
        for (Claim c : claims) {
          permits.acquire();
          executor.execute(
              () -> {
                try {
                  process(c);
                } catch (RuntimeException e) {
                  log.error("order processing failed", e);
                } finally {
                  permits.release();
                  if (permits.availablePermits() == 1) {
                    wakeUp();
                  }
                }
              });
        }
        if (claims.size() < free || free == 0) {
          awaitSignal();
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (RuntimeException e) {
        // Usually the database is unreachable. Keep polling; orders wait in Postgres.
        log.error("saga dispatcher poll failed", e);
        try {
          awaitSignal();
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          return;
        }
      }
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
    dispatcher = Thread.ofPlatform().daemon().name("saga-dispatcher").start(this::dispatch);
  }

  @Override
  public void stop() {
    running = false;
    wakeUp();
    try {
      if (dispatcher != null) {
        dispatcher.join(5_000);
      }
      executor.shutdown();
      if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
        // Leases on the unfinished orders expire and another worker resumes them.
        log.warn("saga steps still running at shutdown");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  /** After the web server, so the in-process simulators are reachable before the first step. */
  @Override
  public int getPhase() {
    return Integer.MAX_VALUE;
  }
}
