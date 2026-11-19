package io.github.laiwf94.payflow.hold;

import static io.github.laiwf94.payflow.order.OrderState.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.laiwf94.payflow.money.Money;
import io.github.laiwf94.payflow.order.CancelReason;
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
import io.github.laiwf94.payflow.sim.FaultPlan;
import io.github.laiwf94.payflow.sim.Faults;
import io.github.laiwf94.payflow.sim.InventorySimulator;
import io.github.laiwf94.payflow.sim.PaymentProviderSimulator;
import io.github.laiwf94.payflow.sim.PaymentProviderSimulator.LedgerEntry;
import io.github.laiwf94.payflow.sim.ShippingSimulator;
import io.github.laiwf94.payflow.support.Api;
import io.github.laiwf94.payflow.support.Db;
import io.github.laiwf94.payflow.support.MutableClock;
import io.github.laiwf94.payflow.support.TestKeys;
import io.github.laiwf94.payflow.timer.TimerHandlers;
import io.github.laiwf94.payflow.timer.TimerProperties;
import io.github.laiwf94.payflow.timer.TimerRepository;
import io.github.laiwf94.payflow.timer.TimerSignal;
import io.github.laiwf94.payflow.timer.TimerWorker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Orders the merchant captures, end to end: the real saga steps over HTTP to the in-process
 * simulators, the wired expiry handlers fired by a timer worker, Postgres, and one clock for the
 * saga, the timers and the capture requests, so an authorization's lifetime can be crossed without
 * waiting for it.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "payflow.holds.reservation-window=1m",
      "payflow.holds.payment-window=10m",
      "payflow.holds.authorization-lifetime=1h",
      // Short enough that a capture asked for late would give up at once if the clock didn't
      // restart; retries alone never give up otherwise.
      "payflow.saga.step-timeout=2m",
      "payflow.saga.max-attempts=1000"
    })
class ManualCaptureTest {

  private static final Duration LIFETIME = Duration.ofHours(1);

  @LocalServerPort int port;
  @Autowired OrderRepository repo;
  @Autowired OrderTransitions transitions;
  @Autowired List<SagaStep> steps;
  @Autowired SagaProperties props;
  @Autowired JdbcClient jdbc;
  @Autowired PlatformTransactionManager txManager;
  @Autowired Faults faults;
  @Autowired PaymentProviderSimulator payments;
  @Autowired InventorySimulator inventory;
  @Autowired ShippingSimulator shipping;
  @Autowired TimerRepository timerRepo;
  @Autowired TimerHandlers handlers;

  private final MutableClock clock = new MutableClock(Instant.now());
  private final Map<String, Consumer<String>> hooks = new HashMap<>();
  private SagaWorker saga;
  private TimerWorker timerWorker;
  private OrderService service;
  private TransactionTemplate tx;

  @BeforeEach
  void setUp() {
    Db.truncateAll(jdbc);
    faults.reset(new SplittableRandom(1));
    payments.reset();
    inventory.reset();
    shipping.reset();
    hooks.clear();
    CrashPoints crash =
        point -> {
          Consumer<String> h = hooks.remove(point);
          if (h != null) {
            h.accept(point);
          }
        };
    saga =
        new SagaWorker(
            repo,
            transitions,
            steps,
            props,
            clock,
            new Backoff(Duration.ofMillis(10), Duration.ofMillis(100), new SplittableRandom(1)),
            crash,
            new SimpleMeterRegistry());
    timerWorker =
        new TimerWorker(
            timerRepo,
            handlers,
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
    // The merchant-facing service on the test's clock, so requests and the saga agree on time.
    service = new OrderService(repo, transitions, saga, clock);
    tx = new TransactionTemplate(txManager);
  }

  private Order capture(String merchant, UUID id) {
    return tx.execute(s -> service.capture(merchant, id));
  }

  private Order cancel(String merchant, UUID id) {
    return tx.execute(s -> service.cancel(merchant, id));
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
            "corr-capture",
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

  private Order order(Order o) {
    return repo.findById(o.id()).orElseThrow();
  }

  /** Moves the clock on and runs both workers until the order is terminal. */
  private Order settle(Order o) {
    for (int i = 0; i < 50 && !order(o).state().isTerminal(); i++) {
      clock.advance(Duration.ofSeconds(1));
      timerWorker.runOnce();
      saga.runOnce();
    }
    return order(o);
  }

  private int timerCount() {
    return jdbc.sql("SELECT count(*) FROM timers").query(Integer.class).single();
  }

  private boolean leased(Order o) {
    return jdbc.sql("SELECT lease_token IS NOT NULL FROM orders WHERE id = :id")
        .param("id", o.id())
        .query(Boolean.class)
        .single();
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

  private static FaultPlan failing() {
    return new FaultPlan(1, 0, 0, Duration.ZERO, List.of());
  }

  @Test
  void aManualOrderWaitsAuthorizedUntilTheMerchantCapturesIt() {
    Instant authorized = clock.instant();
    Order w = waiting();
    assertThat(w.captureMode()).isEqualTo(CaptureMode.MANUAL);
    assertThat(w.captureRequested()).isFalse();
    assertThat(w.authorizationExpiresAt()).isEqualTo(authorized.plus(LIFETIME));
    // Parked off the due list until the authorization runs out, with no lease and no attempts.
    assertThat(w.nextAttemptAt()).isEqualTo(w.authorizationExpiresAt());
    assertThat(w.attempt()).isZero();
    assertThat(leased(w)).isFalse();
    assertThat(operations(w)).containsExactly("authorize");
    // The customer has paid as far as they can: the hold windows no longer apply.
    assertThat(timerCount()).isZero();
    assertThat(inventory.heldFor("sku-1")).isEqualTo(2);

    // Well past both windows, nothing happens.
    clock.advance(Duration.ofMinutes(30));
    assertThat(timerWorker.runOnce()).isZero();
    assertThat(saga.runOnce()).isZero();
    assertThat(order(w)).isEqualTo(w);

    Order asked = capture("acme", w.id());
    assertThat(asked.captureRequested()).isTrue();
    assertThat(asked.state()).isEqualTo(PAYMENT_AUTHORIZED);
    assertThat(asked.nextAttemptAt()).isEqualTo(clock.instant());

    Order done = settle(w);
    assertThat(done.state()).isEqualTo(COMPLETED);
    assertThat(done.capturedMinor()).isEqualTo(2500);
    assertThat(operations(w)).containsExactly("authorize", "capture");
    assertThat(shipping.shipment(w.id().toString())).isPresent();
    assertThat(events(w))
        .containsExactly(
            "order.created",
            "order.inventory_reserved",
            "order.payment_authorized",
            "order.capture_requested",
            "order.payment_captured",
            "order.completed");
    assertThat(
            jdbc.sql(
                    """
                    SELECT DISTINCT payload ->> 'capture_mode' FROM outbox
                    WHERE aggregate_id = :id
                    """)
                .param("id", w.id())
                .query(String.class)
                .list())
        .containsExactly("manual");
    assertThat(timerCount()).isZero();

    // Once it has been captured and completed there is nothing left to capture.
    assertThatThrownBy(() -> capture("acme", w.id()))
        .isInstanceOfSatisfying(
            OrderService.WrongState.class,
            e -> {
              assertThat(e.code()).isEqualTo("order_not_capturable");
              assertThat(e.getMessage()).contains("completed").contains("capture was requested");
            });
  }

  @Test
  void anAutomaticOrderCapturesAtOnceAndRecordsItsAuthorizationExpiry() {
    Instant authorized = clock.instant();
    Order o = create(CaptureMode.AUTOMATIC);
    saga.runOnce();
    Order done = order(o);
    assertThat(done.state()).isEqualTo(COMPLETED);
    assertThat(done.authorizationExpiresAt()).isEqualTo(authorized.plus(LIFETIME));
    assertThat(operations(o)).containsExactly("authorize", "capture");
    assertThat(events(o)).doesNotContain("order.capture_requested");
    assertThat(
            jdbc.sql("SELECT count(*) FROM outbox WHERE payload ->> 'capture_mode' IS NOT NULL")
                .query(Integer.class)
                .single())
        .isZero();
  }

  @Test
  void askingAgainWhileTheCaptureIsOnItsWayAnswersWithTheOrder() {
    Order w = waiting();
    Order first = capture("acme", w.id());
    Order again = capture("acme", w.id());
    assertThat(again).isEqualTo(first);
    assertThat(events(w)).containsOnlyOnce("order.capture_requested");
    assertThat(settle(w).state()).isEqualTo(COMPLETED);
    assertThat(operations(w)).containsExactly("authorize", "capture");
  }

  @Test
  void onlyAManualOrderWaitingWithALiveAuthorizationCanBeCaptured() {
    Order automatic = create(CaptureMode.AUTOMATIC);
    assertThat(notCapturable(automatic)).contains("captures automatically");

    // Not yet authorized.
    faults.set("payment.authorize", failing());
    Order reserved = create(CaptureMode.MANUAL);
    saga.runOnce();
    assertThat(order(reserved).state()).isEqualTo(INVENTORY_RESERVED);
    assertThat(notCapturable(reserved)).contains("inventory_reserved");
    faults.set("payment.authorize", FaultPlan.NONE);

    // Being cancelled.
    Order cancelling = waiting();
    cancel("acme", cancelling.id());
    assertThat(notCapturable(cancelling)).contains("being cancelled");

    // Its authorization has run out, even before the saga noticed.
    Order expired = waiting();
    clock.advance(LIFETIME);
    assertThat(notCapturable(expired)).contains("authorization expired");
    assertThat(order(expired).captureRequested()).isFalse();

    // Missing, or another merchant's, is not found.
    assertThatThrownBy(() -> capture("acme", UUID.randomUUID()))
        .isInstanceOf(OrderService.NotFound.class);
    assertThatThrownBy(() -> capture("globex", expired.id()))
        .isInstanceOf(OrderService.NotFound.class);
    // A refusal writes nothing.
    for (Order o : List.of(automatic, reserved, cancelling, expired)) {
      assertThat(events(o)).doesNotContain("order.capture_requested");
    }
  }

  private String notCapturable(Order o) {
    long version = order(o).version();
    try {
      capture("acme", o.id());
    } catch (OrderService.WrongState e) {
      assertThat(e.code()).isEqualTo("order_not_capturable");
      assertThat(order(o).version()).isEqualTo(version);
      return e.getMessage();
    }
    throw new AssertionError("captured " + o.id());
  }

  @Test
  void anAuthorizationNobodyCapturesExpiresAndTheOrderFailsWithItsStockReleased() {
    Order w = waiting();
    clock.advance(LIFETIME.minusNanos(1_000));
    assertThat(saga.runOnce()).as("not a microsecond early").isZero();
    clock.advance(Duration.ofNanos(1_000));
    assertThat(saga.runOnce()).isOne();

    Order failed = settle(w);
    assertThat(failed.state()).isEqualTo(FAILED);
    assertThat(failed.failureCode()).isEqualTo("authorization_expired");
    assertThat(failed.cancelRequested()).isFalse();
    assertThat(failed.capturedMinor()).isZero();
    assertThat(operations(w)).containsExactly("authorize", "void");
    assertThat(payments.paymentsFor(w.id().toString()))
        .allMatch(p -> p.status() == PaymentProviderSimulator.Status.VOIDED);
    assertThat(inventory.heldFor("sku-1")).isZero();
    assertThat(inventory.heldFor("sku-2")).isZero();
    assertThat(shipping.shipment(w.id().toString())).isEmpty();
    assertThat(events(w))
        .containsExactly(
            "order.created",
            "order.inventory_reserved",
            "order.payment_authorized",
            "order.reversing_payment",
            "order.releasing_inventory",
            "order.failed");
  }

  @Test
  void aCancelWhileWaitingVoidsTheAuthorization() {
    Order w = waiting();
    clock.advance(Duration.ofMinutes(20));
    assertThat(cancel("acme", w.id()).cancelReason()).isEqualTo(CancelReason.REQUESTED);
    Order done = settle(w);
    assertThat(done.state()).isEqualTo(CANCELLED);
    assertThat(done.failureCode()).isNull();
    assertThat(operations(w)).containsExactly("authorize", "void");
    assertThat(inventory.heldFor("sku-1")).isZero();
  }

  @Test
  void aCaptureRequestLandingWhileTheStepDecidesToWaitIsNotLost() {
    // The request commits after the capture step read the order and before its wait is written.
    UUID[] id = new UUID[1];
    hooks.put("after:PAYMENT_AUTHORIZED", p -> capture("acme", id[0]));
    Order o = create(CaptureMode.MANUAL);
    id[0] = o.id();
    saga.runOnce();
    Order due = order(o);
    assertThat(due.state()).isEqualTo(PAYMENT_AUTHORIZED);
    assertThat(due.captureRequested()).isTrue();
    // Due now, not at the end of the authorization's life.
    assertThat(due.nextAttemptAt()).isEqualTo(clock.instant());
    assertThat(leased(due)).isFalse();
    assertThat(saga.runOnce()).isOne();
    assertThat(order(o).state()).isEqualTo(COMPLETED);
    assertThat(operations(o)).containsExactly("authorize", "capture");

    // And a cancel landing at the same moment is not held back either.
    Order other = create(CaptureMode.MANUAL);
    hooks.put("after:PAYMENT_AUTHORIZED", p -> cancel("acme", other.id()));
    saga.runOnce();
    assertThat(order(other).nextAttemptAt()).isEqualTo(clock.instant());
    assertThat(settle(other).state()).isEqualTo(CANCELLED);
    assertThat(operations(other)).containsExactly("authorize", "void");
  }

  @Test
  void aCaptureAskedForLongAfterTheAuthorizationGetsTheWholeStepTimeout() {
    Order w = waiting();
    // 50 minutes later, far past the 2-minute step timeout, the first capture call fails.
    clock.advance(Duration.ofMinutes(50));
    faults.set("payment.capture", failing());
    capture("acme", w.id());
    assertThat(saga.runOnce()).isOne();
    Order retrying = order(w);
    assertThat(retrying.state()).isEqualTo(PAYMENT_AUTHORIZED);
    assertThat(retrying.attempt()).isOne();
    assertThat(retrying.failureCode()).isNull();

    faults.set("payment.capture", FaultPlan.NONE);
    Order done = settle(w);
    assertThat(done.state()).isEqualTo(COMPLETED);
    assertThat(operations(w)).containsExactly("authorize", "capture");
  }

  @Test
  void theHoldWindowsStillApplyUntilAManualOrderIsAuthorized() {
    faults.set("payment.authorize", failing());
    Order o = create(CaptureMode.MANUAL);
    saga.runOnce();
    assertThat(order(o).state()).isEqualTo(INVENTORY_RESERVED);
    assertThat(timerCount()).isEqualTo(2);
    clock.advance(Duration.ofMinutes(1));
    assertThat(timerWorker.runOnce()).isOne();
    Order done = settle(o);
    assertThat(done.state()).isEqualTo(CANCELLED);
    assertThat(done.cancelReason()).isEqualTo(CancelReason.RESERVATION_EXPIRED);
    assertThat(inventory.heldFor("sku-1")).isZero();
  }

  @Test
  void theApiTakesACaptureModeAndShowsIt() {
    Api api = new Api(port, TestKeys.ACME);
    Api.Response automatic = api.post("/v1/orders", Api.orderBody());
    assertThat(automatic.status()).isEqualTo(201);
    assertThat(automatic.json().get("capture_mode").asString()).isEqualTo("automatic");
    assertThat(automatic.json().get("capture_requested").asBoolean()).isFalse();

    Api.Response manual = api.post("/v1/orders", Api.manualOrderBody());
    assertThat(manual.status()).isEqualTo(201);
    assertThat(manual.json().get("capture_mode").asString()).isEqualTo("manual");
    String id = manual.json().get("id").asString();
    assertThat(repo.findById(UUID.fromString(id)).orElseThrow().captureMode())
        .isEqualTo(CaptureMode.MANUAL);

    for (String bad : List.of("\"Manual\"", "\"\"", "7", "true", "null_mode")) {
      String body = Api.orderBody().replaceFirst("\\{", "{\"capture_mode\":" + bad + ",");
      Api.Response r = api.post("/v1/orders", body);
      assertThat(r.status()).as(bad).isEqualTo(400);
      assertThat(r.json().get("error").get("code").asString()).isEqualTo("invalid_argument");
    }
    // An explicit null is the default.
    Api.Response nulled =
        api.post("/v1/orders", Api.orderBody().replaceFirst("\\{", "{\"capture_mode\":null,"));
    assertThat(nulled.status()).isEqualTo(201);
    assertThat(nulled.json().get("capture_mode").asString()).isEqualTo("automatic");

    // Over HTTP, an automatic order can't be captured on request.
    Api.Response refused =
        api.post("/v1/orders/" + automatic.json().get("id").asString() + "/capture", null);
    assertThat(refused.status()).isEqualTo(409);
    assertThat(refused.json().get("error").get("code").asString())
        .isEqualTo("order_not_capturable");
  }
}
