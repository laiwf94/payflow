package io.github.laiwf94.payflow.refund;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.laiwf94.payflow.money.Money;
import io.github.laiwf94.payflow.order.LineItem;
import io.github.laiwf94.payflow.order.Order;
import io.github.laiwf94.payflow.order.OrderRepository;
import io.github.laiwf94.payflow.order.OrderService;
import io.github.laiwf94.payflow.order.OrderState;
import io.github.laiwf94.payflow.order.OrderTransitions;
import io.github.laiwf94.payflow.order.Orders;
import io.github.laiwf94.payflow.saga.Backoff;
import io.github.laiwf94.payflow.saga.CrashPoints;
import io.github.laiwf94.payflow.saga.SagaProperties;
import io.github.laiwf94.payflow.saga.SagaStep;
import io.github.laiwf94.payflow.saga.SagaWorker;
import io.github.laiwf94.payflow.sim.Fault;
import io.github.laiwf94.payflow.sim.FaultPlan;
import io.github.laiwf94.payflow.sim.Faults;
import io.github.laiwf94.payflow.sim.InventorySimulator;
import io.github.laiwf94.payflow.sim.PaymentProviderSimulator;
import io.github.laiwf94.payflow.sim.ShippingSimulator;
import io.github.laiwf94.payflow.sim.SimulatorSettings;
import io.github.laiwf94.payflow.support.Db;
import io.github.laiwf94.payflow.support.MutableClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Refund rows end to end: the service that sizes them under the order's lock, the worker that
 * leases and settles them, and the step that asks the provider before refunding, over the HTTP
 * adapters to the in-process provider and against Postgres.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RefundWorkerTest {

  @Autowired OrderRepository orders;
  @Autowired OrderTransitions orderTransitions;
  @Autowired List<SagaStep> steps;
  @Autowired SagaProperties props;
  @Autowired JdbcClient jdbc;
  @Autowired Faults faults;
  @Autowired SimulatorSettings settings;
  @Autowired PaymentProviderSimulator payments;
  @Autowired InventorySimulator inventory;
  @Autowired ShippingSimulator shipping;
  @Autowired RefundService service;
  @Autowired RefundRepository refunds;
  @Autowired RefundTransitions transitions;
  @Autowired RefundAttempt step;

  // Refunds are created on the application's clock; the workers' clock runs ahead of it, so a new
  // refund is always due for them.
  private final MutableClock clock = new MutableClock(Instant.now().plus(Duration.ofHours(1)));
  private final List<RefundWorker> started = new ArrayList<>();
  private RefundWorker worker;

  @BeforeEach
  void setUp() {
    Db.truncateAll(jdbc);
    faults.reset(new SplittableRandom(1));
    settings.honorIdempotencyKeys(true);
    payments.reset();
    inventory.reset();
    shipping.reset();
    worker = worker(step, CrashPoints.NONE, props);
  }

  @AfterEach
  void stopStarted() {
    started.forEach(RefundWorker::stop);
    settings.honorIdempotencyKeys(true);
  }

  private RefundWorker worker(RefundAttempt attempt, CrashPoints crash, SagaProperties p) {
    return new RefundWorker(
        refunds,
        orders,
        transitions,
        attempt,
        p,
        clock,
        new Backoff(Duration.ofMillis(10), Duration.ofMillis(100), new SplittableRandom(1)),
        crash,
        new SimpleMeterRegistry());
  }

  /** A 25.00 order (two sku-1 at 10.00, one sku-2 at 5.00) taken through its saga. */
  private Order completed() {
    Order o =
        Order.create(
            UUID.randomUUID(),
            "acme",
            Money.of(2500, "EUR"),
            List.of(new LineItem("sku-1", 2, 1000), new LineItem("sku-2", 1, 500)),
            Orders.ADDRESS,
            "pm_card_ok",
            "corr-refund",
            clock.instant());
    orderTransitions.create(o);
    SagaWorker saga =
        new SagaWorker(
            orders,
            orderTransitions,
            steps,
            props,
            clock,
            new Backoff(Duration.ofMillis(10), Duration.ofMillis(100), new SplittableRandom(1)),
            CrashPoints.NONE,
            new SimpleMeterRegistry());
    saga.runOnce();
    Order done = orders.findById(o.id()).orElseThrow();
    assertThat(done.state()).isEqualTo(OrderState.COMPLETED);
    return done;
  }

  private Order order(Order o) {
    return orders.findById(o.id()).orElseThrow();
  }

  private Refund refund(Refund r) {
    return refunds.findById(r.id()).orElseThrow();
  }

  private List<String> events(Order o) {
    return jdbc.sql(
            """
            SELECT event_type || ':' || (payload->>'state') FROM outbox
            WHERE aggregate_id = :id ORDER BY aggregate_seq
            """)
        .param("id", o.id())
        .query(String.class)
        .list();
  }

  /** The order's refund events as {@code type:refund state:refund id:amount}, in sequence. */
  private List<String> refundEvents(Order o) {
    return jdbc.sql(
            """
            SELECT event_type || ':' || (payload->'refund'->>'state') || ':'
                || (payload->'refund'->>'id') || ':' || (payload->'refund'->>'amount')
            FROM outbox
            WHERE aggregate_id = :id AND event_type LIKE 'refund.%'
            ORDER BY aggregate_seq
            """)
        .param("id", o.id())
        .query(String.class)
        .list();
  }

  private long providerRefunds(Order o) {
    return payments.ledgerFor(o.id().toString()).stream()
        .filter(e -> e.operation().equals("refund"))
        .count();
  }

  @Test
  void partialRefundsSettleOnTheirOwnAndAddUpToAFullRefund() {
    Order o = completed();
    Refund a = service.create("acme", o.id(), 1000, List.of(new RefundLine("sku-1", 1)));
    Refund b = service.create("acme", o.id(), 500, List.of());
    assertThat(a.state()).isEqualTo(RefundState.PENDING);
    assertThat(order(o).state())
        .as("nothing moves until the provider answers")
        .isEqualTo(OrderState.COMPLETED);

    assertThat(worker.runOnce()).isEqualTo(2);
    Order partly = order(o);
    assertThat(partly.state()).isEqualTo(OrderState.PARTIALLY_REFUNDED);
    assertThat(partly.refundedMinor()).isEqualTo(1500);
    assertThat(refund(a).state()).isEqualTo(RefundState.SUCCEEDED);
    assertThat(refund(a).lines()).containsExactly(new RefundLine("sku-1", 1));
    assertThat(refund(b).state()).isEqualTo(RefundState.SUCCEEDED);

    assertThat(service.refundRemaining("acme", o.id()).order().state())
        .isEqualTo(OrderState.PARTIALLY_REFUNDED);
    assertThat(worker.runOnce()).isEqualTo(1);
    Order done = order(o);
    assertThat(done.state()).isEqualTo(OrderState.REFUNDED);
    assertThat(done.refundedMinor()).isEqualTo(2500);
    assertThat(worker.runOnce()).as("settled refunds are never claimed again").isZero();

    List<Refund> rows = service.list("acme", o.id());
    assertThat(rows).extracting(Refund::amountMinor).containsExactly(1000L, 500L, 1000L);
    assertThat(payments.refundsFor(done.paymentId()))
        .extracting(PaymentProviderSimulator.Refund::reference)
        .containsExactlyInAnyOrderElementsOf(rows.stream().map(r -> r.id().toString()).toList());
    assertThat(rows)
        .allSatisfy(
            r ->
                assertThat(payments.refundsFor(done.paymentId()))
                    .filteredOn(p -> p.reference().equals(r.id().toString()))
                    .singleElement()
                    .satisfies(
                        p -> {
                          assertThat(p.id()).isEqualTo(r.providerRefundId());
                          assertThat(p.amount()).isEqualTo(r.amountMinor());
                        }));
    assertThat(payments.paymentsFor(o.id().toString()).get(0).refunded()).isEqualTo(2500);
    assertThat(events(o))
        .filteredOn(e -> e.startsWith("order."))
        .endsWith(
            "order.completed:COMPLETED",
            "order.partially_refunded:PARTIALLY_REFUNDED",
            "order.partially_refunded:PARTIALLY_REFUNDED",
            "order.refunded:REFUNDED");
    // Each refund has its own two events in the order's stream, and each success is followed at
    // once by the order change it causes.
    List<String> events = events(o);
    List<String> refundEvents = refundEvents(o);
    for (Refund r : rows) {
      assertThat(refundEvents)
          .filteredOn(e -> e.endsWith(":" + r.id() + ":" + r.amountMinor()))
          .containsExactly(
              "refund.created:PENDING:" + r.id() + ":" + r.amountMinor(),
              "refund.succeeded:SUCCEEDED:" + r.id() + ":" + r.amountMinor());
    }
    for (int i = 0; i < events.size(); i++) {
      if (events.get(i).startsWith("refund.succeeded:")) {
        assertThat(i + 1).as("an order change follows %s", events).isLessThan(events.size());
        assertThat(events.get(i + 1)).startsWith("order.");
        assertThat(events.get(i + 1)).doesNotStartWith("order.completed");
      }
    }
    assertThat(events.indexOf("order.completed:COMPLETED"))
        .as("no refund event before the order completed")
        .isLessThan(
            events.stream()
                .filter(e -> e.startsWith("refund."))
                .findFirst()
                .map(events::indexOf)
                .orElseThrow());
    assertThatThrownBy(() -> service.create("acme", o.id(), 1, List.of()))
        .isInstanceOf(OrderService.WrongState.class)
        .hasMessageContaining("refunded");
  }

  @Test
  void aRefundNeverPromisesMoreThanIsLeft() {
    Order o = completed();
    service.create("acme", o.id(), 2000, List.of());
    assertThatThrownBy(() -> service.create("acme", o.id(), 600, List.of()))
        .isInstanceOfSatisfying(
            RefundService.ExceedsCaptured.class,
            e -> assertThat(e.remaining()).as("pending refunds count").isEqualTo(500));
    assertThatThrownBy(() -> service.create("acme", o.id(), 0, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.create("globex", o.id(), 100, List.of()))
        .isInstanceOf(OrderService.NotFound.class);
    assertThatThrownBy(() -> service.list("globex", o.id()))
        .isInstanceOf(OrderService.NotFound.class);
    assertThatThrownBy(() -> service.refundRemaining("acme", UUID.randomUUID()))
        .isInstanceOf(OrderService.NotFound.class);
    Order pending = Orders.pending("acme", clock.instant());
    orders.insert(pending);
    assertThatThrownBy(() -> service.refundRemaining("acme", pending.id()))
        .isInstanceOf(OrderService.WrongState.class)
        .hasMessageContaining("pending");
    assertThat(refunds.listForOrder(pending.id())).isEmpty();

    // The rest, then nothing: a repeat while it is pending creates no second refund.
    service.refundRemaining("acme", o.id());
    service.refundRemaining("acme", o.id());
    assertThat(refunds.listForOrder(o.id()))
        .extracting(Refund::amountMinor)
        .containsExactly(2000L, 500L);
  }

  @Test
  void racingRequestsAreSizedUnderTheOrderLock() throws Exception {
    Order o = completed();
    int n = 10;
    CyclicBarrier go = new CyclicBarrier(n);
    ExecutorService pool = Executors.newFixedThreadPool(n);
    try {
      List<Future<Boolean>> results = new ArrayList<>();
      for (int i = 0; i < n; i++) {
        results.add(
            pool.submit(
                () -> {
                  go.await(10, TimeUnit.SECONDS);
                  try {
                    service.create("acme", o.id(), 600, List.of());
                    return true;
                  } catch (RefundService.ExceedsCaptured e) {
                    return false;
                  }
                }));
      }
      long made = 0;
      for (Future<Boolean> f : results) {
        made += f.get(30, TimeUnit.SECONDS) ? 1 : 0;
      }
      assertThat(made).as("four 6.00 refunds fit in 25.00").isEqualTo(4);
    } finally {
      pool.shutdownNow();
    }
    assertThat(refunds.activeTotal(o.id())).isEqualTo(2400);
    while (worker.runOnce() > 0) {
      Thread.onSpinWait();
    }
    assertThat(order(o).refundedMinor()).isEqualTo(2400);
    assertThat(order(o).state()).isEqualTo(OrderState.PARTIALLY_REFUNDED);
    assertThat(providerRefunds(o)).isEqualTo(4);
  }

  @Test
  void aRefusedRefundFailsAndGivesItsShareBack() {
    Order o = completed();
    // Someone refunded the payment at the provider directly, so it holds nothing more to return.
    payments.refund(null, o.paymentId(), 2500, "out-of-band");
    Refund r = service.create("acme", o.id(), 1000, List.of());
    worker.runOnce();
    Refund failed = refund(r);
    assertThat(failed.state()).isEqualTo(RefundState.FAILED);
    assertThat(failed.failureCode()).isEqualTo("provider_refused");
    assertThat(failed.failureMessage()).contains("invalid_amount");
    assertThat(failed.nextAttemptAt()).isNull();
    assertThat(order(o).state()).isEqualTo(OrderState.COMPLETED);
    assertThat(order(o).refundedMinor()).isZero();
    assertThat(refunds.activeTotal(o.id())).isZero();
    assertThat(refundEvents(o))
        .containsExactly(
            "refund.created:PENDING:" + r.id() + ":1000",
            "refund.failed:FAILED:" + r.id() + ":1000");
    assertThat(
            jdbc.sql(
                    """
                    SELECT payload->'refund'->>'failure_code' FROM outbox
                    WHERE aggregate_id = :id AND event_type = 'refund.failed'
                    """)
                .param("id", o.id())
                .query(String.class)
                .single())
        .isEqualTo("provider_refused");
    assertThat(events(o))
        .as("a refused refund doesn't move the order")
        .last()
        .isEqualTo("refund.failed:COMPLETED");
    assertThat(service.create("acme", o.id(), 2500, List.of()).amountMinor()).isEqualTo(2500);
  }

  @Test
  void aRefundsEventsCarryItsLinesAndFollowTheOrdersVersion() {
    Order o = completed();
    Refund r = service.create("acme", o.id(), 1000, List.of(new RefundLine("sku-1", 1)));
    Order created = order(o);
    assertThat(created.version())
        .as("the event took a version of its own")
        .isEqualTo(o.version() + 1);
    assertThat(created.updatedAt()).isEqualTo(r.createdAt());
    worker.runOnce();
    Order after = order(o);
    assertThat(after.version())
        .as("refund.succeeded and order.partially_refunded each took one")
        .isEqualTo(o.version() + 3);
    List<Map<String, Object>> rows =
        jdbc.sql(
                """
                SELECT aggregate_seq, event_type, payload::text AS payload FROM outbox
                WHERE aggregate_id = :id AND aggregate_seq > :seq ORDER BY aggregate_seq
                """)
            .param("id", o.id())
            .param("seq", o.version())
            .query()
            .listOfRows();
    assertThat(rows)
        .extracting(m -> m.get("event_type"))
        .containsExactly("refund.created", "refund.succeeded", "order.partially_refunded");
    assertThat(rows)
        .extracting(m -> ((Number) m.get("aggregate_seq")).longValue())
        .containsExactly(o.version() + 1, o.version() + 2, o.version() + 3);
    JsonNode created0 = new ObjectMapper().readTree((String) rows.get(0).get("payload"));
    assertThat(created0.get("seq").asLong()).isEqualTo(o.version() + 1);
    assertThat(created0.get("state").asString()).isEqualTo("COMPLETED");
    assertThat(created0.has("previous_state")).isFalse();
    assertThat(created0.get("refund").get("id").asString()).isEqualTo(r.id().toString());
    assertThat(created0.get("refund").get("amount").asLong()).isEqualTo(1000);
    assertThat(created0.get("refund").get("lines").toString())
        .isEqualTo("[{\"sku\":\"sku-1\",\"quantity\":1}]");
    assertThat(created0.get("refund").has("failure_code")).isFalse();
  }

  @Test
  void anUnavailableProviderIsRetriedWithBackoff() {
    Order o = completed();
    faults.set(
        "payment.refund", new FaultPlan(0, 0, 0, Duration.ZERO, List.of(Fault.ERROR, Fault.ERROR)));
    Refund r = service.create("acme", o.id(), 2500, List.of());
    worker.runOnce();
    Refund waiting = refund(r);
    assertThat(waiting.state()).isEqualTo(RefundState.PENDING);
    assertThat(waiting.attempt()).isEqualTo(1);
    assertThat(waiting.nextAttemptAt()).isAfter(clock.instant());
    assertThat(worker.runOnce()).as("not due yet").isZero();
    for (int i = 0; i < 5 && refund(r).state() == RefundState.PENDING; i++) {
      clock.advance(Duration.ofSeconds(1));
      worker.runOnce();
    }
    assertThat(refund(r).state()).isEqualTo(RefundState.SUCCEEDED);
    assertThat(refund(r).attempt()).isEqualTo(2);
    assertThat(order(o).state()).isEqualTo(OrderState.REFUNDED);
    assertThat(providerRefunds(o)).isEqualTo(1);
  }

  @Test
  void aWorkerThatDiesAfterTheProviderRefundedIsResumedWithoutRefundingTwice() {
    // Without the provider's deduplication, only the lookup stands between a resume and a
    // second refund.
    settings.honorIdempotencyKeys(false);
    Order o = completed();
    Refund r = service.create("acme", o.id(), 1200, List.of());
    RefundWorker dying =
        worker(
            step,
            point -> {
              if (point.equals("after:REFUND")) {
                throw new Crash();
              }
            },
            props);
    assertThatThrownBy(dying::runOnce).isInstanceOf(Crash.class);
    assertThat(providerRefunds(o)).isEqualTo(1);
    assertThat(refund(r).state()).isEqualTo(RefundState.PENDING);
    assertThat(worker.runOnce()).as("the dead worker's lease still holds").isZero();

    clock.advance(props.lease().plusSeconds(1));
    List<RefundClaim> claims = refunds.claimDue(clock.instant(), props.lease(), 10);
    assertThat(claims).singleElement().satisfies(c -> assertThat(c.recovered()).isTrue());
    worker.process(claims.get(0));
    assertThat(refund(r).state()).isEqualTo(RefundState.SUCCEEDED);
    assertThat(refund(r).providerRefundId())
        .isEqualTo(payments.refundsFor(o.paymentId()).get(0).id());
    assertThat(providerRefunds(o)).isEqualTo(1);
    assertThat(order(o).refundedMinor()).isEqualTo(1200);
  }

  @Test
  void aWorkerThatLostItsLeaseCannotSettleTheRefund() {
    Order o = completed();
    Refund r = service.create("acme", o.id(), 700, List.of());
    RefundClaim claim = refunds.claimDue(clock.instant(), props.lease(), 10).get(0);
    Instant now = clock.instant();
    assertThat(transitions.succeed(r, UUID.randomUUID(), "re_x", now)).isEmpty();
    assertThat(transitions.fail(r, UUID.randomUUID(), "x", "y", now)).isEmpty();
    assertThat(refunds.retry(r.id(), UUID.randomUUID(), 1, now, now)).isFalse();
    assertThat(order(o).refundedMinor()).as("the order is untouched").isZero();
    assertThat(transitions.succeed(r, claim.leaseToken(), "re_x", now)).isPresent();
    assertThat(transitions.succeed(r, claim.leaseToken(), "re_x", now))
        .as("settled once")
        .isEmpty();
    assertThat(order(o).refundedMinor()).isEqualTo(700);
  }

  @Test
  void stoppingHandsBackARefundStillWaitingForTheProvider() throws Exception {
    Order o = completed();
    CountDownLatch inCall = new CountDownLatch(1);
    CountDownLatch never = new CountDownLatch(1);
    RefundAttempt hanging =
        (refund, order, resumed) -> {
          inCall.countDown();
          try {
            never.await();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          return new RefundOutcome.Succeeded("re_late");
        };
    SagaProperties running =
        new SagaProperties(
            true,
            Duration.ofSeconds(90),
            Duration.ofMillis(20),
            8,
            8,
            Duration.ofMillis(10),
            Duration.ofMillis(100),
            8,
            Duration.ofMinutes(2),
            Duration.ofSeconds(1),
            Duration.ofMillis(300));
    RefundWorker w = worker(hanging, CrashPoints.NONE, running);
    w.start();
    assertThat(w.isRunning()).isTrue();
    Refund r = service.create("acme", o.id(), 2500, List.of());
    w.wakeUp();
    assertThat(inCall.await(10, TimeUnit.SECONDS)).isTrue();

    long t0 = System.nanoTime();
    w.stop();
    long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
    assertThat(tookMs).as("waits out the grace, no longer").isBetween(250L, 3_000L);
    assertThat(w.isRunning()).isFalse();
    Refund handed = refund(r);
    assertThat(handed.state())
        .as("the interrupted call's late success is fenced out")
        .isEqualTo(RefundState.PENDING);
    assertThat(order(o).refundedMinor()).isZero();
    List<RefundClaim> next = refunds.claimDue(clock.instant(), props.lease(), 10);
    assertThat(next)
        .as("claimable at once, as a recovery")
        .singleElement()
        .satisfies(c -> assertThat(c.recovered()).isTrue());
    worker.process(next.get(0));
    assertThat(refund(r).state()).isEqualTo(RefundState.SUCCEEDED);
    assertThat(order(o).state()).isEqualTo(OrderState.REFUNDED);
    assertThat(providerRefunds(o)).isEqualTo(1);
  }

  @Test
  void theBackgroundWorkerSettlesARefundAsSoonAsItCommits() throws Exception {
    Order o = completed();
    SagaProperties running =
        new SagaProperties(
            true,
            Duration.ofSeconds(30),
            // A long poll, so only the wake-up after commit can explain a quick refund.
            Duration.ofSeconds(30),
            8,
            8,
            Duration.ofMillis(10),
            Duration.ofMillis(100),
            8,
            Duration.ofMinutes(2),
            Duration.ofSeconds(1),
            Duration.ofSeconds(1));
    RefundWorker w = worker(step, CrashPoints.NONE, running);
    started.add(w);
    w.start();
    // Let the dispatcher run its first, empty poll and go to sleep.
    Thread.sleep(200);
    Refund r = service.create("acme", o.id(), 900, List.of());
    w.wakeUp();
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (refund(r).state() == RefundState.PENDING && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertThat(refund(r).state()).isEqualTo(RefundState.SUCCEEDED);
    assertThat(order(o).refundedMinor()).isEqualTo(900);
  }

  /** Stands in for a killed process: an Error, so no worker code catches it. */
  static final class Crash extends Error {
    private static final long serialVersionUID = 1L;
  }
}
