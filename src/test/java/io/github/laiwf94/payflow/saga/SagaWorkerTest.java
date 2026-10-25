package io.github.laiwf94.payflow.saga;

import static io.github.laiwf94.payflow.order.OrderState.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.github.laiwf94.payflow.order.Order;
import io.github.laiwf94.payflow.order.OrderRepository;
import io.github.laiwf94.payflow.order.OrderState;
import io.github.laiwf94.payflow.order.OrderTransitions;
import io.github.laiwf94.payflow.order.Orders;
import io.github.laiwf94.payflow.saga.StepOutcome.Fields;
import io.github.laiwf94.payflow.support.Db;
import io.github.laiwf94.payflow.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The worker's mechanics with scripted steps, against real Postgres. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = "payflow.saga.enabled=false")
class SagaWorkerTest {

  @Autowired OrderRepository repo;
  @Autowired OrderTransitions transitions;
  @Autowired JdbcClient jdbc;

  private final MutableClock clock = new MutableClock(Instant.now());
  private final Map<OrderState, BiFunction<Order, Boolean, StepOutcome>> script =
      new EnumMap<>(OrderState.class);
  private final List<String> calls = new ArrayList<>();

  @BeforeEach
  void setUp() {
    Db.truncateAll(jdbc);
    script.put(
        PENDING, (o, r) -> new StepOutcome.Advance(INVENTORY_RESERVED, Fields.reservation("r")));
    script.put(
        INVENTORY_RESERVED,
        (o, r) -> new StepOutcome.Advance(PAYMENT_AUTHORIZED, Fields.payment("p")));
    script.put(
        PAYMENT_AUTHORIZED,
        (o, r) ->
            new StepOutcome.Advance(PAYMENT_CAPTURED, Fields.money(o.amount().minorUnits(), 0)));
    script.put(
        PAYMENT_CAPTURED, (o, r) -> new StepOutcome.Advance(COMPLETED, Fields.shipment("s", "t")));
    script.put(CANCELLING_SHIPMENT, (o, r) -> new StepOutcome.Advance(REVERSING_PAYMENT));
    script.put(
        REVERSING_PAYMENT,
        (o, r) ->
            new StepOutcome.Advance(
                RELEASING_INVENTORY, Fields.money(o.capturedMinor(), o.capturedMinor())));
    script.put(
        RELEASING_INVENTORY,
        (o, r) -> new StepOutcome.Advance(o.cancelRequested() ? CANCELLED : FAILED));
  }

  private SagaProperties props(int maxAttempts, Duration stepTimeout, boolean enabled) {
    return new SagaProperties(
        enabled,
        Duration.ofSeconds(30),
        Duration.ofMillis(20),
        64,
        64,
        Duration.ofMillis(100),
        Duration.ofSeconds(5),
        maxAttempts,
        stepTimeout,
        Duration.ofSeconds(2));
  }

  private SagaWorker worker(SagaProperties p, CrashPoints crash) {
    List<SagaStep> steps = new ArrayList<>();
    for (OrderState s : script.keySet()) {
      steps.add(
          new SagaStep() {
            @Override
            public OrderState handles() {
              return s;
            }

            @Override
            public StepOutcome execute(Order order, boolean resumed) {
              synchronized (calls) {
                calls.add(order.id() + ":" + s + ":" + resumed);
              }
              return script.get(s).apply(order, resumed);
            }
          });
    }
    return new SagaWorker(
        repo,
        transitions,
        steps,
        p,
        clock,
        new Backoff(p.backoffBase(), p.backoffCap(), new SplittableRandom(1)),
        crash,
        new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
  }

  private SagaWorker worker() {
    return worker(props(5, Duration.ofMinutes(2), false), CrashPoints.NONE);
  }

  private Order insert() {
    Order o = Orders.pending("acme", clock.instant());
    repo.insert(o);
    return o;
  }

  private Order load(Order o) {
    return repo.findById(o.id()).orElseThrow();
  }

  @Test
  void runsTheWholeSagaUnderOneLease() {
    Order o = insert();
    assertThat(worker().runOnce()).isEqualTo(1);
    Order done = load(o);
    assertThat(done.state()).isEqualTo(COMPLETED);
    assertThat(done.capturedMinor()).isEqualTo(2500);
    assertThat(done.trackingNumber()).isEqualTo("t");
    assertThat(done.nextAttemptAt()).isNull();
    assertThat(calls).hasSize(4).allMatch(c -> c.endsWith(":false"));
  }

  @Test
  void retriesWithBackoffAndTellsTheStepItIsResumed() {
    AtomicInteger n = new AtomicInteger();
    script.put(
        INVENTORY_RESERVED,
        (o, r) ->
            n.incrementAndGet() == 1
                ? new StepOutcome.Retry("provider busy", Duration.ofSeconds(1))
                : new StepOutcome.Advance(PAYMENT_AUTHORIZED, Fields.payment("p")));
    Order o = insert();
    SagaWorker w = worker();
    w.runOnce();
    Order waiting = load(o);
    assertThat(waiting.state()).isEqualTo(INVENTORY_RESERVED);
    assertThat(waiting.attempt()).isEqualTo(1);
    assertThat(waiting.nextAttemptAt()).isAfterOrEqualTo(clock.instant().plusSeconds(1));
    assertThat(w.runOnce()).as("not due yet").isZero();
    clock.advance(Duration.ofSeconds(2));
    assertThat(w.runOnce()).isEqualTo(1);
    assertThat(load(o).state()).isEqualTo(COMPLETED);
    assertThat(calls).anyMatch(c -> c.endsWith("INVENTORY_RESERVED:true"));
  }

  @Test
  void aForwardStepThatKeepsFailingIsCompensated() {
    script.put(PAYMENT_AUTHORIZED, (o, r) -> new StepOutcome.Retry("still down"));
    Order o = insert();
    SagaWorker w = worker(props(3, Duration.ofMinutes(2), false), CrashPoints.NONE);
    for (int i = 0; i < 5 && !load(o).state().isTerminal(); i++) {
      w.runOnce();
      clock.advance(Duration.ofSeconds(10));
    }
    Order failed = load(o);
    assertThat(failed.state()).isEqualTo(FAILED);
    assertThat(failed.failureCode()).isEqualTo("step_timeout");
    assertThat(failed.failureMessage()).startsWith("payment capture did not succeed in time");
  }

  @Test
  void theStepTimeoutCountsWallTimeAcrossRetries() {
    script.put(PENDING, (o, r) -> new StepOutcome.Retry("inventory down"));
    Order o = insert();
    SagaWorker w = worker(props(100, Duration.ofSeconds(30), false), CrashPoints.NONE);
    w.runOnce();
    clock.advance(Duration.ofSeconds(31));
    w.runOnce();
    assertThat(load(o).state()).isEqualTo(FAILED);
    assertThat(load(o).failureMessage()).contains("inventory reservation");
  }

  @Test
  void compensationsNeverGiveUp() {
    AtomicInteger n = new AtomicInteger();
    script.put(
        PENDING, (o, r) -> new StepOutcome.Fail("out_of_stock", "sold out", RELEASING_INVENTORY));
    script.put(
        RELEASING_INVENTORY,
        (o, r) ->
            n.incrementAndGet() < 12
                ? new StepOutcome.Retry("inventory down")
                : new StepOutcome.Advance(FAILED));
    Order o = insert();
    SagaWorker w = worker(props(2, Duration.ofSeconds(1), false), CrashPoints.NONE);
    for (int i = 0; i < 20 && !load(o).state().isTerminal(); i++) {
      w.runOnce();
      clock.advance(Duration.ofMinutes(1));
    }
    assertThat(load(o).state()).isEqualTo(FAILED);
    assertThat(load(o).failureCode()).isEqualTo("out_of_stock");
    assertThat(n).hasValue(12);
  }

  @Test
  void aCancelRequestDivertsTheNextForwardStep() {
    Order o = insert();
    jdbc.sql("UPDATE orders SET cancel_requested = true WHERE id = :id")
        .param("id", o.id())
        .update();
    worker().runOnce();
    assertThat(load(o).state()).isEqualTo(CANCELLED);
    assertThat(calls).noneMatch(c -> c.contains(":PENDING:"));
  }

  @Test
  void anUnexpectedExceptionIsRetried() {
    AtomicInteger n = new AtomicInteger();
    script.put(
        PENDING,
        (o, r) -> {
          if (n.incrementAndGet() == 1) {
            throw new IllegalStateException("bug");
          }
          return new StepOutcome.Advance(INVENTORY_RESERVED, Fields.reservation("r"));
        });
    Order o = insert();
    SagaWorker w = worker();
    w.runOnce();
    assertThat(load(o).attempt()).isEqualTo(1);
    clock.advance(Duration.ofSeconds(10));
    w.runOnce();
    assertThat(load(o).state()).isEqualTo(COMPLETED);
  }

  @Test
  void aMissingStepIsRetriedNotSkipped() {
    script.remove(PAYMENT_CAPTURED);
    Order o = insert();
    worker().runOnce();
    assertThat(load(o).state()).isEqualTo(PAYMENT_CAPTURED);
    assertThat(load(o).attempt()).isEqualTo(1);
  }

  @Test
  void aCrashLeavesTheLeaseAndTheNextWorkerResumes() {
    Order o = insert();
    CrashPoints crashAfterAuthorize =
        p -> {
          if (p.equals("after:INVENTORY_RESERVED")) {
            throw new SimulatedCrash();
          }
        };
    assertThatThrownBy(
            () -> worker(props(5, Duration.ofMinutes(2), false), crashAfterAuthorize).runOnce())
        .isInstanceOf(SimulatedCrash.class);
    assertThat(load(o).state()).isEqualTo(INVENTORY_RESERVED);
    SagaWorker fresh = worker();
    assertThat(fresh.runOnce()).as("lease still held").isZero();
    clock.advance(Duration.ofSeconds(31));
    assertThat(fresh.runOnce()).isEqualTo(1);
    assertThat(load(o).state()).isEqualTo(COMPLETED);
    assertThat(calls)
        .containsSubsequence(
            o.id() + ":INVENTORY_RESERVED:false", o.id() + ":INVENTORY_RESERVED:true");
  }

  @Test
  void aWorkerThatLostItsLeaseCannotWrite() {
    Order o = insert();
    UUID stolen = UUID.randomUUID();
    script.put(
        PENDING,
        (order, r) -> {
          // Another worker takes over while this step is "slow".
          jdbc.sql("UPDATE orders SET lease_token = :t WHERE id = :id")
              .param("t", stolen)
              .param("id", order.id())
              .update();
          return new StepOutcome.Advance(INVENTORY_RESERVED, Fields.reservation("r"));
        });
    worker().runOnce();
    assertThat(load(o).state()).isEqualTo(PENDING);
  }

  @Test
  void concurrentWorkersNeverRunTheSameStepTwice() throws Exception {
    List<Order> orders = new ArrayList<>();
    for (int i = 0; i < 60; i++) {
      orders.add(insert());
    }
    List<Thread> threads = new ArrayList<>();
    for (int t = 0; t < 6; t++) {
      SagaWorker w = worker();
      threads.add(
          Thread.ofPlatform()
              .start(
                  () -> {
                    while (w.runOnce() > 0) {
                      Thread.onSpinWait();
                    }
                  }));
    }
    for (Thread t : threads) {
      t.join();
    }
    Map<String, Integer> perStep = new ConcurrentHashMap<>();
    calls.forEach(c -> perStep.merge(c, 1, Integer::sum));
    assertThat(perStep.values()).allMatch(v -> v == 1);
    assertThat(perStep).hasSize(60 * 4);
    assertThat(orders).allMatch(o -> load(o).state() == COMPLETED);
  }

  @Test
  void theBackgroundDispatcherDrainsWork() {
    SagaWorker w = worker(props(5, Duration.ofMinutes(2), true), CrashPoints.NONE);
    w.start();
    try {
      assertThat(w.isRunning()).isTrue();
      List<Order> orders = new ArrayList<>();
      for (int i = 0; i < 20; i++) {
        orders.add(insert());
      }
      w.wakeUp();
      await()
          .atMost(Duration.ofSeconds(20))
          .until(() -> orders.stream().allMatch(o -> load(o).state() == COMPLETED));
    } finally {
      w.stop();
    }
    assertThat(w.isRunning()).isFalse();
    assertThat(w.getPhase()).isEqualTo(Integer.MAX_VALUE);
  }

  @Test
  void disabledWorkerDoesNotStartAndTwoStepsForOneStateAreRefused() {
    SagaWorker w = worker();
    w.start();
    assertThat(w.isRunning()).isFalse();
    w.stop();
    SagaStep a =
        new SagaStep() {
          @Override
          public OrderState handles() {
            return PENDING;
          }

          @Override
          public StepOutcome execute(Order order, boolean resumed) {
            return new StepOutcome.Retry("x");
          }
        };
    SagaProperties p = props(5, Duration.ofMinutes(1), false);
    assertThatThrownBy(
            () ->
                new SagaWorker(
                    repo,
                    transitions,
                    List.of(a, a),
                    p,
                    clock,
                    new Backoff(p.backoffBase(), p.backoffCap(), new SplittableRandom()),
                    CrashPoints.NONE,
                    new io.micrometer.core.instrument.simple.SimpleMeterRegistry()))
        .hasMessageContaining("two steps");
  }

  @Test
  void productionWiringRefusesToStartWithAStateNobodyHandles() {
    assertThatThrownBy(() -> SagaConfig.requireEveryStep(List.of()))
        .hasMessageContaining("PENDING")
        .hasMessageContaining("REFUNDING");
  }

  @Test
  void propertiesAreValidated() {
    assertThatThrownBy(() -> props(0, Duration.ofSeconds(1), false))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new SagaProperties(
                    true,
                    Duration.ofMillis(10),
                    Duration.ofMillis(10),
                    1,
                    1,
                    Duration.ofMillis(1),
                    Duration.ofMillis(1),
                    1,
                    Duration.ofSeconds(1),
                    Duration.ZERO))
        .hasMessageContaining("lease");
    assertThatThrownBy(
            () ->
                new SagaProperties(
                    true,
                    Duration.ofSeconds(10),
                    Duration.ofMillis(10),
                    0,
                    1,
                    Duration.ofMillis(1),
                    Duration.ofMillis(1),
                    1,
                    Duration.ofSeconds(1),
                    Duration.ZERO))
        .hasMessageContaining("batch size");
  }

  /** Stands in for the process dying: an Error, so no step or worker code catches it. */
  static final class SimulatedCrash extends Error {
    private static final long serialVersionUID = 1L;
  }
}
