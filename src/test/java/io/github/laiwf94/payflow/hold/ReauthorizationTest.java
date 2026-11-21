package io.github.laiwf94.payflow.hold;

import static io.github.laiwf94.payflow.order.OrderState.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.laiwf94.payflow.money.Money;
import io.github.laiwf94.payflow.order.CaptureMode;
import io.github.laiwf94.payflow.order.LineItem;
import io.github.laiwf94.payflow.order.Order;
import io.github.laiwf94.payflow.order.OrderRepository;
import io.github.laiwf94.payflow.order.OrderService;
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
import io.github.laiwf94.payflow.sim.PaymentProviderSimulator.LedgerEntry;
import io.github.laiwf94.payflow.sim.ShippingSimulator;
import io.github.laiwf94.payflow.sim.SimulatorSettings;
import io.github.laiwf94.payflow.support.Api;
import io.github.laiwf94.payflow.support.Db;
import io.github.laiwf94.payflow.support.MutableClock;
import io.github.laiwf94.payflow.support.TestKeys;
import io.github.laiwf94.payflow.timer.TimerHandler;
import io.github.laiwf94.payflow.timer.TimerHandlers;
import io.github.laiwf94.payflow.timer.TimerProperties;
import io.github.laiwf94.payflow.timer.TimerRepository;
import io.github.laiwf94.payflow.timer.TimerSignal;
import io.github.laiwf94.payflow.timer.TimerWorker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.SplittableRandom;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Manual-capture orders whose authorization is extended before it lapses, end to end: the real saga
 * steps over HTTP to the in-process provider, the wired {@code order.reauthorization} timer fired
 * by a timer worker, Postgres, and one clock shared by the saga, the timers, the merchant's
 * requests and the provider, so lifetimes are crossed without waiting for them. Authorizations last
 * an hour at the provider and are extended ten minutes before they lapse.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "payflow.holds.reservation-window=1m",
      "payflow.holds.payment-window=10m",
      "payflow.holds.authorization-lifetime=1h",
      "payflow.holds.reauthorize-before=10m",
      "payflow.simulator.authorization-lifetime=1h",
      // An extension is asked for 50 minutes after the authorization; without a fresh step clock
      // its first retry would give up at once.
      "payflow.saga.step-timeout=2m",
      "payflow.saga.max-attempts=1000"
    })
class ReauthorizationTest {

  private static final Duration LIFETIME = Duration.ofHours(1);
  private static final Duration BEFORE = Duration.ofMinutes(10);
  private static final Duration MICRO = Duration.ofNanos(1_000);

  @LocalServerPort int port;
  @Autowired OrderRepository repo;
  @Autowired OrderTransitions transitions;
  @Autowired List<SagaStep> steps;
  @Autowired SagaProperties props;
  @Autowired JdbcClient jdbc;
  @Autowired PlatformTransactionManager txManager;
  @Autowired Faults faults;
  @Autowired SimulatorSettings simSettings;
  @Autowired PaymentProviderSimulator payments;
  @Autowired InventorySimulator inventory;
  @Autowired ShippingSimulator shipping;
  @Autowired TimerRepository timerRepo;
  @Autowired List<ExpiryHandler> expiryHandlers;
  @Autowired ObjectProvider<SagaWorker> wiredSaga;

  private final MutableClock clock = new MutableClock(Instant.now());
  private SagaWorker saga;
  private ReauthorizationHandler reauthorizationHandler;
  private TimerWorker timerWorker;
  private OrderService service;
  private TransactionTemplate tx;

  @BeforeEach
  void setUp() {
    Db.truncateAll(jdbc);
    faults.reset(new SplittableRandom(1));
    payments.reset();
    payments.useClock(clock);
    inventory.reset();
    shipping.reset();
    saga =
        new SagaWorker(
            repo,
            transitions,
            steps,
            props,
            clock,
            new Backoff(Duration.ofMillis(10), Duration.ofMillis(100), new SplittableRandom(1)),
            CrashPoints.NONE,
            new SimpleMeterRegistry());
    // The extension handler on the test's clock, so the step clock it restarts is the saga's.
    reauthorizationHandler =
        new ReauthorizationHandler(repo, wiredSaga, clock, new SimpleMeterRegistry());
    List<TimerHandler> all = new java.util.ArrayList<>(expiryHandlers);
    all.add(reauthorizationHandler);
    timerWorker =
        new TimerWorker(
            timerRepo,
            new TimerHandlers(all),
            new TimerProperties(
                false,
                Duration.ofMillis(100),
                256,
                8,
                Duration.ofSeconds(30),
                Duration.ofMillis(10),
                Duration.ofMillis(100),
                Duration.ZERO),
            new TimerSignal(),
            txManager,
            clock,
            new Backoff(Duration.ofMillis(10), Duration.ofMillis(100), new SplittableRandom(1)),
            CrashPoints.NONE,
            new SimpleMeterRegistry());
    service = new OrderService(repo, transitions, saga, clock);
    tx = new TransactionTemplate(txManager);
  }

  @AfterEach
  void restoreProvider() {
    simSettings.honorIdempotencyKeys(true);
    payments.reset();
  }

  private Order create(CaptureMode mode) {
    Order o =
        Order.create(
            UUID.randomUUID(),
            "acme",
            null,
            mode,
            Money.of(2500, "EUR"),
            List.of(new LineItem("sku-1", 2, 1000), new LineItem("sku-2", 1, 500)),
            Orders.ADDRESS,
            "pm_card_ok",
            "corr-reauth",
            clock.instant());
    assertThat(transitions.create(o)).isTrue();
    return o;
  }

  /** A manual-capture order taken as far as it goes on its own: authorized, and waiting. */
  private Order waiting() {
    Order o = create(CaptureMode.MANUAL);
    saga.runOnce();
    Order w = order(o);
    assertThat(w.state()).isEqualTo(PAYMENT_AUTHORIZED);
    return w;
  }

  /** {@link #waiting()}, then on to the moment the extension is due, with the timer fired. */
  private Order dueForExtension() {
    Order w = waiting();
    clock.advance(LIFETIME.minus(BEFORE));
    assertThat(timerWorker.runOnce()).isOne();
    return order(w);
  }

  private Order order(Order o) {
    return repo.findById(o.id()).orElseThrow();
  }

  private Order capture(UUID id) {
    return tx.execute(s -> service.capture("acme", id));
  }

  /** Moves the clock on a second at a time, running both workers, until the order is terminal. */
  private Order settle(Order o) {
    for (int i = 0; i < 50 && !order(o).state().isTerminal(); i++) {
      clock.advance(Duration.ofSeconds(1));
      timerWorker.runOnce();
      saga.runOnce();
    }
    return order(o);
  }

  private List<String> timers(Order o) {
    return jdbc.sql("SELECT kind FROM timers WHERE subject_id = :id ORDER BY kind")
        .param("id", o.id())
        .query(String.class)
        .list();
  }

  private Instant timerDue(Order o, String kind) {
    return jdbc.sql("SELECT due_at FROM timers WHERE subject_id = :id AND kind = :kind")
        .param("id", o.id())
        .param("kind", kind)
        .query(java.time.OffsetDateTime.class)
        .single()
        .toInstant();
  }

  private List<String> events(Order o) {
    return jdbc.sql("SELECT event_type FROM outbox WHERE aggregate_id = :id ORDER BY aggregate_seq")
        .param("id", o.id())
        .query(String.class)
        .list();
  }

  private List<String> operations(Order o) {
    return payments.ledgerFor(o.id().toString()).stream().map(LedgerEntry::operation).toList();
  }

  private PaymentProviderSimulator.Payment payment(Order o) {
    return payments.paymentsFor(o.id().toString()).getFirst();
  }

  private void assertStockReleasedAndNothingShipped(Order o) {
    assertThat(inventory.heldFor("sku-1")).isZero();
    assertThat(inventory.heldFor("sku-2")).isZero();
    assertThat(shipping.shipment(o.id().toString())).isEmpty();
  }

  @Test
  void aWaitingOrderIsExtendedOnceBeforeItsHoldLapsesAndCapturedAfterTheFirstExpiry() {
    Instant authorized = clock.instant();
    Order w = waiting();
    assertThat(w.authorizationExpiresAt())
        .as("the provider's own expiry")
        .isEqualTo(payment(w).expiresAt())
        .isEqualTo(authorized.plus(LIFETIME));
    assertThat(timers(w)).containsExactly(OrderHolds.REAUTHORIZATION);
    assertThat(timerDue(w, OrderHolds.REAUTHORIZATION))
        .isEqualTo(w.authorizationExpiresAt().minus(BEFORE));

    clock.advance(LIFETIME.minus(BEFORE).minus(MICRO));
    assertThat(timerWorker.runOnce()).as("not a microsecond early").isZero();
    assertThat(saga.runOnce()).isZero();
    clock.advance(MICRO);
    assertThat(timerWorker.runOnce()).isOne();
    Order asked = order(w);
    assertThat(asked.reauthorizationRequested()).isTrue();
    assertThat(asked.reauthorizedAt()).isNull();
    assertThat(asked.nextAttemptAt()).isEqualTo(clock.instant());
    assertThat(asked.stepStartedAt()).isEqualTo(clock.instant());

    Instant extendedAt = clock.instant();
    assertThat(saga.runOnce()).isOne();
    Order extended = order(w);
    assertThat(extended.state()).isEqualTo(PAYMENT_AUTHORIZED);
    assertThat(extended.reauthorizedAt()).isEqualTo(extendedAt);
    assertThat(extended.authorizationExpiresAt())
        .isEqualTo(extendedAt.plus(LIFETIME))
        .isEqualTo(payment(w).expiresAt());
    assertThat(extended.nextAttemptAt())
        .as("waiting again, until the new expiry")
        .isEqualTo(extended.authorizationExpiresAt());
    assertThat(extended.attempt()).isZero();
    assertThat(timers(w)).as("once: no second extension is scheduled").isEmpty();
    assertThat(operations(w)).containsExactly("authorize", "reauthorize");
    assertThat(events(w))
        .containsExactly(
            "order.created",
            "order.inventory_reserved",
            "order.payment_authorized",
            "order.payment_reauthorized");
    assertThat(
            jdbc.sql(
                    """
                    SELECT payload ->> 'authorization_expires_at' FROM outbox
                    WHERE aggregate_id = :id AND event_type = 'order.payment_reauthorized'
                    """)
                .param("id", w.id())
                .query(String.class)
                .single())
        .isEqualTo(extended.authorizationExpiresAt().toString());

    // Past the first expiry the order and the provider both still hold the money.
    clock.advance(BEFORE.plus(Duration.ofMinutes(20)));
    assertThat(timerWorker.runOnce()).isZero();
    assertThat(saga.runOnce()).isZero();
    assertThat(payment(w).status()).isEqualTo(PaymentProviderSimulator.Status.AUTHORIZED);

    Api.Response shown = new Api(port, TestKeys.ACME).get("/v1/orders/" + w.id());
    assertThat(shown.status()).isEqualTo(200);
    assertThat(shown.json().get("payment").get("reauthorized_at").asString())
        .isEqualTo(extendedAt.toString());
    assertThat(shown.json().get("payment").get("authorization_expires_at").asString())
        .isEqualTo(extended.authorizationExpiresAt().toString());

    capture(w.id());
    Order done = settle(w);
    assertThat(done.state()).isEqualTo(COMPLETED);
    assertThat(done.capturedMinor()).isEqualTo(2500);
    assertThat(operations(w)).containsExactly("authorize", "reauthorize", "capture");
    assertThat(payments.ledgerFor(w.id().toString()))
        .as("no capture exceeds what was authorized")
        .allMatch(e -> e.amount() == 2500);
    assertThat(shipping.shipment(w.id().toString())).isPresent();
    assertThat(events(w))
        .endsWith(
            "order.payment_reauthorized",
            "order.capture_requested",
            "order.payment_captured",
            "order.completed");
  }

  @Test
  void anOrderStillWaitingAfterItsExtensionFailsAtTheNewExpiryWithoutASecondOne() {
    Order extended = dueForExtension();
    saga.runOnce();
    extended = order(extended);
    assertThat(extended.reauthorizedAt()).isNotNull();

    clock.advance(LIFETIME.minus(MICRO));
    assertThat(timerWorker.runOnce()).isZero();
    assertThat(saga.runOnce()).as("not a microsecond early").isZero();
    clock.advance(MICRO);
    assertThat(saga.runOnce()).isOne();

    Order failed = settle(extended);
    assertThat(failed.state()).isEqualTo(FAILED);
    assertThat(failed.failureCode()).isEqualTo("authorization_expired");
    assertThat(failed.capturedMinor()).isZero();
    assertThat(operations(failed)).containsExactly("authorize", "reauthorize");
    assertThat(payment(failed).status()).isEqualTo(PaymentProviderSimulator.Status.EXPIRED);
    assertStockReleasedAndNothingShipped(failed);
    assertThat(events(failed))
        .containsOnlyOnce("order.payment_reauthorized")
        .endsWith("order.failed");
  }

  @Test
  void aHoldTheIssuerDroppedEarlyCannotBeExtendedAndTheOrderFailsWithItsStockReleased() {
    Order w = waiting();
    clock.advance(Duration.ofMinutes(20));
    assertThat(payments.expireNow(w.paymentId())).isTrue();
    // Nothing notices until the extension is due: payflow still thinks it has 40 minutes.
    assertThat(saga.runOnce()).isZero();

    clock.advance(LIFETIME.minus(BEFORE).minus(Duration.ofMinutes(20)));
    assertThat(timerWorker.runOnce()).isOne();
    Order failed = settle(w);
    assertThat(failed.state()).isEqualTo(FAILED);
    assertThat(failed.failureCode()).isEqualTo("authorization_expired");
    assertThat(failed.failureMessage()).contains("before it could be extended");
    assertThat(failed.reauthorizedAt()).isNull();
    assertThat(operations(w)).containsExactly("authorize");
    assertStockReleasedAndNothingShipped(w);
    assertThat(events(w)).doesNotContain("order.payment_reauthorized");
    assertThat(timers(w)).isEmpty();
  }

  @Test
  void aCaptureOfAHoldTheIssuerDroppedEarlyFailsTheOrderWithNothingTaken() {
    Order w = waiting();
    clock.advance(Duration.ofMinutes(5));
    payments.expireNow(w.paymentId());
    // payflow's recorded expiry is still ahead, so the request is accepted.
    assertThat(capture(w.id()).captureRequested()).isTrue();
    Order failed = settle(w);
    assertThat(failed.state()).isEqualTo(FAILED);
    assertThat(failed.failureCode()).isEqualTo("authorization_expired");
    assertThat(failed.failureMessage()).contains("before the capture");
    assertThat(failed.capturedMinor()).isZero();
    assertThat(operations(w)).containsExactly("authorize");
    assertStockReleasedAndNothingShipped(w);
    assertThat(timers(w)).as("leaving PAYMENT_AUTHORIZED cancels the extension").isEmpty();
  }

  @Test
  void aLostAnswerIsFoundOnTheRetryAndTheExtensionIsNeverRepeated() {
    // Keys off: only the lookup before the retry keeps the second call from extending again.
    simSettings.honorIdempotencyKeys(false);
    faults.set(
        "payment.reauthorize", new FaultPlan(0, 0, 0, Duration.ZERO, List.of(Fault.UNKNOWN_AFTER)));
    Order after = dueForExtension();
    Instant extendedAt = clock.instant();
    assertThat(saga.runOnce()).isOne();
    assertThat(order(after).reauthorizedAt()).as("the answer was lost").isNull();
    assertThat(order(after).attempt()).isOne();

    faults.set(
        "payment.reauthorize",
        new FaultPlan(0, 0, 0, Duration.ZERO, List.of(Fault.UNKNOWN_BEFORE)));
    // Fifty minutes on, the first order's retry is due as well.
    Order before = dueForExtension();
    assertThat(saga.runOnce()).isEqualTo(2);

    for (int i = 0; i < 10; i++) {
      clock.advance(Duration.ofSeconds(1));
      saga.runOnce();
    }
    Order a = order(after);
    assertThat(a.reauthorizedAt()).isEqualTo(extendedAt);
    assertThat(a.authorizationExpiresAt())
        .as("the extension the lost call made")
        .isEqualTo(extendedAt.plus(LIFETIME))
        .isEqualTo(payment(a).expiresAt());
    assertThat(operations(a)).containsExactly("authorize", "reauthorize");

    Order b = order(before);
    assertThat(b.reauthorizedAt()).isNotNull();
    assertThat(b.authorizationExpiresAt()).isEqualTo(payment(b).expiresAt());
    assertThat(operations(b))
        .as("the call that never arrived is made once, after the lookup")
        .containsExactly("authorize", "reauthorize");
    for (Order o : List.of(a, b)) {
      assertThat(o.state()).isEqualTo(PAYMENT_AUTHORIZED);
      assertThat(events(o)).containsOnlyOnce("order.payment_reauthorized");
    }
  }

  @Test
  void anOutageRetriesOnAFreshStepClockRatherThanGivingUp() {
    faults.set(
        "payment.reauthorize",
        new FaultPlan(0, 0, 0, Duration.ZERO, List.of(Fault.ERROR, Fault.ERROR, Fault.ERROR)));
    Order w = dueForExtension();
    for (int i = 0; i < 10 && order(w).reauthorizedAt() == null; i++) {
      saga.runOnce();
      clock.advance(Duration.ofSeconds(1));
    }
    Order extended = order(w);
    assertThat(extended.state()).isEqualTo(PAYMENT_AUTHORIZED);
    assertThat(extended.reauthorizedAt()).isNotNull();
    assertThat(extended.failureCode()).isNull();
    assertThat(operations(w)).containsExactly("authorize", "reauthorize");
  }

  @Test
  void aCaptureOrCancelLeavesNothingToExtend() {
    // Asked to capture before the extension is due: captured, and the timer goes with the state.
    Order captured = waiting();
    clock.advance(Duration.ofMinutes(30));
    capture(captured.id());
    assertThat(settle(captured).state()).isEqualTo(COMPLETED);
    assertThat(timers(captured)).isEmpty();

    // Cancelled while waiting: voided, and no timer left.
    Order cancelled = waiting();
    tx.execute(s -> service.cancel("acme", cancelled.id()));
    assertThat(settle(cancelled).state()).isEqualTo(CANCELLED);
    assertThat(timers(cancelled)).isEmpty();
    assertThat(operations(cancelled)).containsExactly("authorize", "void");

    // Asked to capture after the timer fired but before the saga ran: the capture wins.
    Order raced = dueForExtension();
    assertThat(raced.reauthorizationRequested()).isTrue();
    capture(raced.id());
    assertThat(settle(raced).state()).isEqualTo(COMPLETED);
    assertThat(operations(raced)).containsExactly("authorize", "capture");
    assertThat(order(raced).reauthorizedAt()).isNull();

    for (Order o : List.of(captured, cancelled, raced)) {
      assertThat(operations(o)).doesNotContain("reauthorize");
      assertThat(events(o)).doesNotContain("order.payment_reauthorized");
    }
  }

  @Test
  void aTimerForAnOrderThatMovedOnChangesNothing() {
    Order automatic = create(CaptureMode.AUTOMATIC);
    saga.runOnce();
    assertThat(order(automatic).state()).isEqualTo(COMPLETED);
    assertThat(timers(automatic)).as("an automatic order never waits").isEmpty();

    Order asked = dueForExtension();
    Order pending = create(CaptureMode.MANUAL);
    for (UUID id : List.of(automatic.id(), asked.id(), pending.id(), UUID.randomUUID())) {
      long version = repo.findById(id).map(Order::version).orElse(-1L);
      tx.executeWithoutResult(
          s ->
              reauthorizationHandler.fire(
                  new io.github.laiwf94.payflow.timer.Timer(
                      OrderHolds.REAUTHORIZATION,
                      id,
                      clock.instant(),
                      0,
                      null,
                      clock.instant(),
                      clock.instant())));
      assertThat(repo.findById(id).map(Order::version).orElse(-1L))
          .as("untouched: %s", id)
          .isEqualTo(version);
    }
  }

  @Test
  void aHoldThatLapsedBeforeItsLostAnswerWasFoundIsNeverAuthorizedAgain() {
    for (boolean keys : List.of(false, true)) {
      simSettings.honorIdempotencyKeys(keys);
      faults.set(
          "payment.authorize", new FaultPlan(0, 0, 0, Duration.ZERO, List.of(Fault.UNKNOWN_AFTER)));
      Order o = create(CaptureMode.AUTOMATIC);
      saga.runOnce();
      assertThat(order(o).state()).as("answer lost, keys %s", keys).isEqualTo(INVENTORY_RESERVED);
      assertThat(operations(o)).containsExactly("authorize");
      // The issuer drops the hold before the saga's retry finds it.
      assertThat(payments.expireNow(payment(o).id())).isTrue();

      Order done = settle(o);
      assertThat(done.state()).as("keys %s", keys).isEqualTo(FAILED);
      assertThat(done.failureCode()).isEqualTo("authorization_expired");
      assertThat(operations(o)).as("authorized once, keys %s", keys).containsExactly("authorize");
      assertThat(payments.paymentsFor(o.id().toString()))
          .singleElement()
          .extracting(PaymentProviderSimulator.Payment::status)
          .isEqualTo(PaymentProviderSimulator.Status.EXPIRED);
      assertStockReleasedAndNothingShipped(o);
      assertThat(timers(o)).isEmpty();
      assertThat(events(o))
          .containsExactly(
              "order.created",
              "order.inventory_reserved",
              "order.releasing_inventory",
              "order.failed");
    }
  }

  @Test
  void theLeadMustBeShorterThanTheLifetime() {
    Duration h = Duration.ofHours(1);
    assertThat(new HoldProperties(h, h, h, Duration.ofMinutes(59)).reauthorizes()).isTrue();
    assertThat(new HoldProperties(h, h, h, Duration.ZERO).reauthorizes()).isFalse();
    for (Duration bad : List.of(h, Duration.ofHours(2), Duration.ofSeconds(-1))) {
      assertThatThrownBy(() -> new HoldProperties(h, h, h, bad))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("reauthorize-before");
    }
  }
}
