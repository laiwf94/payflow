package io.github.laiwf94.payflow.hold;

import static io.github.laiwf94.payflow.order.OrderState.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.laiwf94.payflow.money.Money;
import io.github.laiwf94.payflow.order.CancelReason;
import io.github.laiwf94.payflow.order.LineItem;
import io.github.laiwf94.payflow.order.Order;
import io.github.laiwf94.payflow.order.OrderRepository;
import io.github.laiwf94.payflow.order.OrderService;
import io.github.laiwf94.payflow.order.OrderState;
import io.github.laiwf94.payflow.order.OrderTransitions;
import io.github.laiwf94.payflow.order.Orders;
import io.github.laiwf94.payflow.order.StateChange;
import io.github.laiwf94.payflow.outbox.OutboxWriter;
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
import io.github.laiwf94.payflow.support.Db;
import io.github.laiwf94.payflow.support.MutableClock;
import io.github.laiwf94.payflow.timer.Timer;
import io.github.laiwf94.payflow.timer.TimerHandlers;
import io.github.laiwf94.payflow.timer.TimerProperties;
import io.github.laiwf94.payflow.timer.TimerRepository;
import io.github.laiwf94.payflow.timer.TimerService;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unpaid orders running out of time, end to end: the real saga steps over HTTP to the in-process
 * simulators, the wired expiry handlers fired by a timer worker, Postgres, and one clock for both
 * workers so a window can be crossed without waiting for it.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "payflow.holds.reservation-window=1m",
      "payflow.holds.payment-window=10m",
      // Retries alone never give up within these tests, so only a hold can end a stuck order.
      "payflow.saga.step-timeout=1h",
      "payflow.saga.max-attempts=1000"
    })
class HoldExpiryTest {

  private static final Duration RESERVATION = Duration.ofMinutes(1);
  private static final Duration PAYMENT = Duration.ofMinutes(10);

  @Autowired OrderRepository repo;
  @Autowired OrderTransitions transitions;
  @Autowired OrderService orders;
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
  @Autowired TimerService timers;
  @Autowired OutboxWriter outbox;
  @Autowired ObjectProvider<SagaWorker> wiredSaga;

  @Autowired
  @Qualifier("reservationExpiryHandler")
  ExpiryHandler reservationExpiry;

  private final MutableClock clock = new MutableClock(Instant.now());
  private final Map<String, Consumer<String>> hooks = new HashMap<>();
  private SagaWorker saga;
  private TimerWorker timerWorker;
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
    tx = new TransactionTemplate(txManager);
  }

  /** A new order created the way the API creates one, so it gets its payment window. */
  private Order create() {
    Order o =
        Order.create(
            UUID.randomUUID(),
            "acme",
            Money.of(2500, "EUR"),
            List.of(new LineItem("sku-1", 2, 1000), new LineItem("sku-2", 1, 500)),
            Orders.ADDRESS,
            "pm_card_ok",
            "corr-hold",
            clock.instant());
    assertThat(transitions.create(o)).isTrue();
    return o;
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

  private Instant due(String kind, Order o) {
    return timers.find(kind, o.id()).map(Timer::dueAt).orElse(null);
  }

  private int timerCount() {
    return jdbc.sql("SELECT count(*) FROM timers").query(Integer.class).single();
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

  private String cancelReasonInEvent(Order o) {
    return jdbc.sql(
            """
            SELECT payload ->> 'cancel_reason' FROM outbox
            WHERE aggregate_id = :id AND event_type = 'order.cancel_requested'
            """)
        .param("id", o.id())
        .query(String.class)
        .single();
  }

  private static FaultPlan failing() {
    return new FaultPlan(1, 0, 0, Duration.ZERO, List.of());
  }

  @Test
  void aPaidOrderLeavesNoTimersBehind() {
    Instant created = clock.instant();
    Order o = create();
    assertThat(due(OrderHolds.PAYMENT_EXPIRY, o)).isEqualTo(created.plus(PAYMENT));
    assertThat(due(OrderHolds.RESERVATION_EXPIRY, o)).isNull();

    // Reserved, then stuck on authorization: both windows are running.
    faults.set("payment.authorize", failing());
    clock.advance(Duration.ofSeconds(3));
    Instant reserved = clock.instant();
    saga.runOnce();
    assertThat(order(o).state()).isEqualTo(INVENTORY_RESERVED);
    assertThat(due(OrderHolds.RESERVATION_EXPIRY, o)).isEqualTo(reserved.plus(RESERVATION));
    assertThat(due(OrderHolds.PAYMENT_EXPIRY, o)).isEqualTo(created.plus(PAYMENT));

    // Capture ends both: there is nothing left to expire.
    faults.set("payment.authorize", FaultPlan.NONE);
    Order done = settle(o);
    assertThat(done.state()).isEqualTo(COMPLETED);
    assertThat(done.cancelRequested()).isFalse();
    assertThat(done.cancelReason()).isNull();
    assertThat(timerCount()).isZero();

    // Well past both windows, nothing fires and nothing changes.
    clock.advance(PAYMENT.multipliedBy(2));
    assertThat(timerWorker.runOnce()).isZero();
    assertThat(order(o).version()).isEqualTo(done.version());
  }

  @Test
  void stockHeldPastTheReservationWindowIsReleasedAndTheOrderCancelledOnce() {
    faults.set("payment.authorize", failing());
    Order o = create();
    saga.runOnce();
    assertThat(order(o).state()).isEqualTo(INVENTORY_RESERVED);
    assertThat(inventory.heldFor("sku-1")).isEqualTo(2);

    // Not a microsecond early.
    clock.advance(RESERVATION.minusNanos(1_000));
    saga.runOnce();
    assertThat(timerWorker.runOnce()).isZero();
    assertThat(order(o).cancelRequested()).isFalse();

    clock.advance(Duration.ofNanos(1_000));
    assertThat(timerWorker.runOnce()).isOne();
    Order flagged = order(o);
    assertThat(flagged.cancelRequested()).isTrue();
    assertThat(flagged.cancelReason()).isEqualTo(CancelReason.RESERVATION_EXPIRED);
    assertThat(flagged.nextAttemptAt()).isBeforeOrEqualTo(clock.instant());

    Order done = settle(o);
    assertThat(done.state()).isEqualTo(CANCELLED);
    assertThat(done.cancelReason()).isEqualTo(CancelReason.RESERVATION_EXPIRED);
    assertThat(done.failureCode()).isNull();
    assertThat(inventory.heldFor("sku-1")).isZero();
    assertThat(inventory.heldFor("sku-2")).isZero();
    assertThat(operations(o)).isEmpty();
    assertThat(events(o))
        .containsExactly(
            "order.created",
            "order.inventory_reserved",
            "order.cancel_requested",
            "order.reversing_payment",
            "order.releasing_inventory",
            "order.cancelled");
    assertThat(cancelReasonInEvent(o)).isEqualTo("reservation_expired");
    // The payment window's timer went when the order started to compensate.
    assertThat(timerCount()).isZero();
  }

  @Test
  void anAuthorizedButUncapturedOrderIsVoidedWhenItsReservationRunsOut() {
    faults.set("payment.capture", failing());
    Order o = create();
    saga.runOnce();
    assertThat(order(o).state()).isEqualTo(PAYMENT_AUTHORIZED);

    clock.advance(RESERVATION);
    assertThat(timerWorker.runOnce()).isOne();
    Order done = settle(o);
    assertThat(done.state()).isEqualTo(CANCELLED);
    assertThat(done.cancelReason()).isEqualTo(CancelReason.RESERVATION_EXPIRED);
    assertThat(done.capturedMinor()).isZero();
    assertThat(operations(o)).containsExactly("authorize", "void");
    assertThat(payments.paymentsFor(o.id().toString()))
        .allMatch(p -> p.status() == PaymentProviderSimulator.Status.VOIDED);
    assertThat(inventory.heldFor("sku-1")).isZero();
    assertThat(timerCount()).isZero();
  }

  @Test
  void anOrderNeverPaidWithinThePaymentWindowIsCancelled() {
    // Inventory is down: the order never gets past PENDING, so no reservation window starts.
    faults.set("inventory.reserve", failing());
    Order o = create();
    saga.runOnce();
    assertThat(order(o).state()).isEqualTo(PENDING);
    assertThat(due(OrderHolds.RESERVATION_EXPIRY, o)).isNull();

    clock.advance(PAYMENT.minusSeconds(1));
    saga.runOnce();
    assertThat(timerWorker.runOnce()).isZero();
    clock.advance(Duration.ofSeconds(1));
    assertThat(timerWorker.runOnce()).isOne();

    Order done = settle(o);
    assertThat(done.state()).isEqualTo(CANCELLED);
    assertThat(done.cancelReason()).isEqualTo(CancelReason.PAYMENT_EXPIRED);
    assertThat(done.failureCode()).isNull();
    assertThat(operations(o)).isEmpty();
    assertThat(inventory.reservation(o.id().toString())).isEmpty();
    assertThat(events(o))
        .containsExactly(
            "order.created",
            "order.cancel_requested",
            "order.releasing_inventory",
            "order.cancelled");
    assertThat(cancelReasonInEvent(o)).isEqualTo("payment_expired");
    assertThat(timerCount()).isZero();
  }

  @Test
  void theFirstReasonToCancelIsTheOneKept() {
    faults.set("payment.authorize", failing());
    Order o = create();
    saga.runOnce();
    // The merchant cancels; before the saga gets to it, the reservation also runs out.
    assertThat(orders.cancel("acme", o.id()).cancelReason()).isEqualTo(CancelReason.REQUESTED);
    clock.advance(RESERVATION);
    assertThat(timerWorker.runOnce()).isOne();
    assertThat(order(o).cancelReason()).isEqualTo(CancelReason.REQUESTED);
    assertThat(events(o)).containsOnlyOnce("order.cancel_requested");

    Order done = settle(o);
    assertThat(done.state()).isEqualTo(CANCELLED);
    assertThat(done.cancelReason()).isEqualTo(CancelReason.REQUESTED);
    // A merchant cancel on an order already expiring is accepted and keeps the expiry's reason.
    Order other = create();
    saga.runOnce();
    clock.advance(RESERVATION);
    timerWorker.runOnce();
    assertThat(orders.cancel("acme", other.id()).cancelReason())
        .isEqualTo(CancelReason.RESERVATION_EXPIRED);
  }

  @Test
  void aTimerFiringAfterTheOrderMovedOnChangesNothing() {
    Order o = create();
    Order done = settle(o);
    assertThat(done.state()).isEqualTo(COMPLETED);
    // A timer left behind by an older version, or scheduled by hand, finds nothing to do.
    tx.executeWithoutResult(
        s -> {
          timers.schedule(OrderHolds.RESERVATION_EXPIRY, o.id(), clock.instant());
          timers.schedule(OrderHolds.PAYMENT_EXPIRY, o.id(), clock.instant());
        });
    assertThat(timerWorker.runOnce()).isEqualTo(2);
    assertThat(order(o)).isEqualTo(done);
    assertThat(timerCount()).isZero();
    // And for an order that doesn't exist at all.
    tx.executeWithoutResult(
        s -> timers.schedule(OrderHolds.PAYMENT_EXPIRY, UUID.randomUUID(), clock.instant()));
    assertThat(timerWorker.runOnce()).isOne();
    assertThat(timerCount()).isZero();
  }

  @Test
  void aFencedMoveKeepsTheTimersItWouldHaveCancelled() {
    faults.set("payment.capture", failing());
    Order o = create();
    saga.runOnce();
    Order parked = order(o);
    assertThat(parked.state()).isEqualTo(PAYMENT_AUTHORIZED);
    assertThat(timerCount()).isEqualTo(2);

    Instant now = clock.instant();
    assertThat(
            transitions.apply(
                parked,
                UUID.randomUUID(),
                StateChange.to(PAYMENT_CAPTURED)
                    .stepStartedAt(now)
                    .nextAttemptAt(now)
                    .capturedMinor(2500)
                    .build(),
                now))
        .isEmpty();
    assertThat(order(o)).isEqualTo(parked);
    assertThat(due(OrderHolds.RESERVATION_EXPIRY, o)).isNotNull();
    assertThat(due(OrderHolds.PAYMENT_EXPIRY, o)).isNotNull();
  }

  @Test
  void anExpiryRacingACaptureNeitherDeadlocksNorKeepsTheMoney() throws Exception {
    faults.set("payment.capture", failing());
    Order o = create();
    saga.runOnce();
    assertThat(order(o).state()).isEqualTo(PAYMENT_AUTHORIZED);
    faults.set("payment.capture", FaultPlan.NONE);
    clock.advance(RESERVATION);
    Timer timer = timers.find(OrderHolds.RESERVATION_EXPIRY, o.id()).orElseThrow();

    // A fire takes the timer's row, the way TimerWorker's fenced delete does, and holds it while
    // the saga captures and tries to cancel that same timer; then it expires the order.
    CountDownLatch rowTaken = new CountDownLatch(1);
    CompletableFuture<Void> fire = new CompletableFuture<>();
    hooks.put(
        "before:PAYMENT_AUTHORIZED",
        p -> {
          Thread.ofVirtual()
              .start(
                  () -> {
                    try {
                      tx.executeWithoutResult(
                          s -> {
                            jdbc.sql("DELETE FROM timers WHERE kind = :k AND subject_id = :id")
                                .param("k", OrderHolds.RESERVATION_EXPIRY)
                                .param("id", o.id())
                                .update();
                            rowTaken.countDown();
                            sleep(300);
                            reservationExpiry.fire(timer);
                          });
                      fire.complete(null);
                    } catch (RuntimeException e) {
                      fire.completeExceptionally(e);
                    }
                  });
          try {
            assertThat(rowTaken.await(10, TimeUnit.SECONDS)).isTrue();
          } catch (InterruptedException e) {
            throw new IllegalStateException(e);
          }
        });
    saga.runOnce();
    fire.get(10, TimeUnit.SECONDS);

    // The capture went through and committed, but the expiry was accepted first in the order's
    // life, so the money goes back and the order still ends cancelled with nothing kept.
    Order done = settle(o);
    assertThat(done.state()).isEqualTo(CANCELLED);
    assertThat(done.cancelReason()).isEqualTo(CancelReason.RESERVATION_EXPIRED);
    assertThat(done.capturedMinor()).isEqualTo(2500);
    assertThat(done.refundedMinor()).isEqualTo(2500);
    assertThat(operations(o)).containsExactly("authorize", "capture", "refund");
    assertThat(shipping.shipment(o.id().toString())).isEmpty();
    assertThat(inventory.heldFor("sku-1")).isZero();
    assertThat(timerCount()).isZero();
  }

  @Test
  void handlersAndSettingsAreChecked() {
    assertThat(handlers.kinds()).contains(OrderHolds.RESERVATION_EXPIRY, OrderHolds.PAYMENT_EXPIRY);
    assertThatThrownBy(
            () ->
                new ExpiryHandler(
                    "order.cancel",
                    CancelReason.REQUESTED,
                    repo,
                    outbox,
                    wiredSaga,
                    clock,
                    new SimpleMeterRegistry()))
        .isInstanceOf(IllegalArgumentException.class);
    Duration s = Duration.ofSeconds(1);
    assertThat(new HoldProperties(s, HoldProperties.MAX_WINDOW, s).paymentWindow())
        .isEqualTo(Duration.ofDays(31));
    assertThat(new HoldProperties(s, s, HoldProperties.MAX_WINDOW).authorizationLifetime())
        .isEqualTo(Duration.ofDays(31));
    for (Duration bad : List.of(Duration.ofMillis(999), Duration.ofDays(32), Duration.ZERO)) {
      assertThatThrownBy(() -> new HoldProperties(bad, s, s))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> new HoldProperties(s, bad, s))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> new HoldProperties(s, s, bad))
          .isInstanceOf(IllegalArgumentException.class);
    }
    for (CancelReason r : CancelReason.values()) {
      assertThat(CancelReason.fromCode(r.code())).isEqualTo(r);
      assertThat(r.appliesTo()).allMatch(OrderState::isCancellable);
    }
    assertThat(CancelReason.fromCode(null)).isNull();
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
