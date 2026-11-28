package io.github.laiwf94.payflow.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.github.laiwf94.payflow.money.Money;
import io.github.laiwf94.payflow.order.CaptureMode;
import io.github.laiwf94.payflow.order.LineItem;
import io.github.laiwf94.payflow.order.Order;
import io.github.laiwf94.payflow.order.OrderRepository;
import io.github.laiwf94.payflow.order.OrderState;
import io.github.laiwf94.payflow.order.OrderTransitions;
import io.github.laiwf94.payflow.order.OrderTransitions.Tender;
import io.github.laiwf94.payflow.order.Orders;
import io.github.laiwf94.payflow.payment.Payment;
import io.github.laiwf94.payflow.payment.PaymentRepository;
import io.github.laiwf94.payflow.payment.PaymentState;
import io.github.laiwf94.payflow.refund.AllocationPolicy;
import io.github.laiwf94.payflow.refund.Refund;
import io.github.laiwf94.payflow.refund.RefundAttempt;
import io.github.laiwf94.payflow.refund.RefundProperties;
import io.github.laiwf94.payflow.refund.RefundRepository;
import io.github.laiwf94.payflow.refund.RefundService;
import io.github.laiwf94.payflow.refund.RefundState;
import io.github.laiwf94.payflow.refund.RefundTransitions;
import io.github.laiwf94.payflow.refund.RefundWorker;
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
import io.github.laiwf94.payflow.sim.SimulatorConfig;
import io.github.laiwf94.payflow.support.Db;
import io.github.laiwf94.payflow.support.MutableClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.SplittableRandom;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tenders routed to two providers, end to end: the router in the order's insert, the real payment
 * and refund steps, both HTTP adapters, both in-process provider simulators and Postgres. Euro
 * cards go to the second provider, and so does everything of merchant {@code globex}; the rest goes
 * to the first.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "payflow.routing.default-provider=primary",
      "payflow.routing.rules[0].id=eur-cards",
      "payflow.routing.rules[0].provider=secondary",
      "payflow.routing.rules[0].currencies[0]=EUR",
      "payflow.routing.rules[0].methods[0]=pm_card_*",
      "payflow.routing.rules[1].id=globex",
      "payflow.routing.rules[1].provider=secondary",
      "payflow.routing.rules[1].merchants[0]=globex"
    })
class RoutingSagaTest {

  @Autowired OrderRepository orders;
  @Autowired OrderTransitions transitions;
  @Autowired PaymentRepository tenders;
  @Autowired List<SagaStep> steps;
  @Autowired SagaProperties props;
  @Autowired JdbcClient jdbc;
  @Autowired Faults faults;
  @Autowired PaymentProviderSimulator primary;

  @Autowired
  @Qualifier(SimulatorConfig.SECONDARY)
  PaymentProviderSimulator secondary;

  @Autowired InventorySimulator inventory;
  @Autowired ShippingSimulator shipping;
  @Autowired RefundRepository refunds;
  @Autowired RefundTransitions refundTransitions;
  @Autowired RefundAttempt refundStep;
  @Autowired RefundWorker contextRefundWorker;
  @Autowired Clock appClock;
  @Autowired PlatformTransactionManager txManager;

  // Ahead of the application's clock, so a refund made on it is always due for the workers.
  private final MutableClock clock = new MutableClock(Instant.now().plus(Duration.ofHours(1)));
  private SagaWorker worker;

  @BeforeEach
  void setUp() {
    Db.truncateAll(jdbc);
    faults.reset(new SplittableRandom(1));
    primary.reset();
    secondary.reset();
    inventory.reset();
    shipping.reset();
    worker =
        new SagaWorker(
            orders,
            transitions,
            steps,
            props,
            clock,
            new Backoff(Duration.ofMillis(10), Duration.ofMillis(100), new SplittableRandom(1)),
            CrashPoints.NONE,
            new SimpleMeterRegistry());
  }

  private Order order(String merchant, String currency, Tender... split) {
    Order o =
        Order.create(
            UUID.randomUUID(),
            merchant,
            null,
            CaptureMode.AUTOMATIC,
            Money.of(2500, currency),
            List.of(new LineItem("sku-1", 2, 1000), new LineItem("sku-2", 1, 500)),
            Orders.ADDRESS,
            split[0].method(),
            "corr-routing",
            clock.instant());
    assertThat(
            (split.length == 1 && split[0].amount().equals(o.amount()))
                ? transitions.create(o)
                : transitions.create(o, List.of(split)))
        .isTrue();
    return o;
  }

  private static Tender tender(String method, long amount) {
    return new Tender(method, Money.of(amount, "EUR"));
  }

  private Order drive(Order o) {
    for (int i = 0; i < 50; i++) {
      Order now = orders.findById(o.id()).orElseThrow();
      if (now.state().isTerminal()) {
        return now;
      }
      clock.advance(Duration.ofSeconds(5));
      worker.runOnce();
    }
    return orders.findById(o.id()).orElseThrow();
  }

  private List<Payment> rows(Order o) {
    return tenders.forOrder(o.id());
  }

  /** A provider's ledger for every tender of the order, as {@code operation reference amount}. */
  private static List<String> ledger(PaymentProviderSimulator sim, Order o) {
    return sim.ledger().stream()
        .filter(e -> e.reference().startsWith(o.id().toString()))
        .map(RoutingSagaTest::entry)
        .toList();
  }

  private static String entry(LedgerEntry e) {
    String ref = e.reference();
    int dash = ref.length() > 36 ? 36 : -1;
    return e.operation() + " " + (dash < 0 ? "1" : ref.substring(dash + 1)) + " " + e.amount();
  }

  @Test
  void aEuroCardOrderRunsItsWholeSagaAtTheSecondProvider() {
    Order o = order("acme", "EUR", tender("pm_card_ok", 2500));
    assertThat(rows(o))
        .extracting(Payment::provider, Payment::routingRule, Payment::state)
        .containsExactly(tuple("secondary", "eur-cards", PaymentState.PENDING));

    Order done = drive(o);

    assertThat(done.state()).isEqualTo(OrderState.COMPLETED);
    assertThat(done.paymentId()).startsWith("psp_");
    assertThat(ledger(secondary, o)).containsExactly("authorize 1 2500", "capture 1 2500");
    assertThat(ledger(primary, o)).isEmpty();
    assertThat(rows(o))
        .extracting(Payment::provider, Payment::state, Payment::capturedMinor)
        .containsExactly(tuple("secondary", PaymentState.CAPTURED, 2500L));
  }

  @Test
  void aTenderNoRuleMatchesTakesTheDefaultRouteAndAMerchantRuleCoversEveryMethod() {
    Order usd = order("acme", "USD", new Tender("pm_card_ok", Money.of(2500, "USD")));
    Order gift = order("acme", "EUR", tender("pm_giftcard_ok", 2500));
    Order globex = order("globex", "USD", new Tender("pm_giftcard_ok", Money.of(2500, "USD")));

    assertThat(rows(usd))
        .extracting(Payment::provider, Payment::routingRule)
        .containsExactly(tuple("primary", "default"));
    assertThat(rows(gift))
        .extracting(Payment::provider, Payment::routingRule)
        .containsExactly(tuple("primary", "default"));
    assertThat(rows(globex))
        .extracting(Payment::provider, Payment::routingRule)
        .containsExactly(tuple("secondary", "globex"));

    assertThat(drive(usd).state()).isEqualTo(OrderState.COMPLETED);
    assertThat(drive(globex).state()).isEqualTo(OrderState.COMPLETED);
    assertThat(ledger(primary, usd)).containsExactly("authorize 1 2500", "capture 1 2500");
    assertThat(ledger(secondary, usd)).isEmpty();
    assertThat(ledger(secondary, globex)).containsExactly("authorize 1 2500", "capture 1 2500");
    assertThat(ledger(primary, globex)).isEmpty();
  }

  @Test
  void aSplitOrderPaysEachTenderAtItsOwnProvider() {
    Order o = order("acme", "EUR", tender("pm_giftcard_ok", 1000), tender("pm_card_ok", 1500));
    assertThat(rows(o))
        .extracting(Payment::position, Payment::provider, Payment::routingRule)
        .containsExactly(tuple(1, "primary", "default"), tuple(2, "secondary", "eur-cards"));

    assertThat(drive(o).state()).isEqualTo(OrderState.COMPLETED);

    assertThat(ledger(primary, o)).containsExactly("authorize 1 1000", "capture 1 1000");
    assertThat(ledger(secondary, o)).containsExactly("authorize 2 1500", "capture 2 1500");
    assertThat(rows(o))
        .extracting(Payment::providerPaymentId)
        .satisfiesExactly(
            id -> assertThat(id).startsWith("pay_"), id -> assertThat(id).startsWith("psp_"));
  }

  @Test
  void aDeclineAtTheSecondProviderVoidsTheTenderHeldAtTheFirst() {
    Order o =
        order("acme", "EUR", tender("pm_giftcard_ok", 1000), tender("pm_card_declined", 1500));

    Order done = drive(o);

    assertThat(done.state()).isEqualTo(OrderState.FAILED);
    assertThat(done.failureCode()).isEqualTo("payment_declined");
    assertThat(done.failureMessage()).isEqualTo("tender 2 declined: card_declined");
    assertThat(ledger(primary, o)).containsExactly("authorize 1 1000", "void 1 1000");
    assertThat(ledger(secondary, o)).containsExactly("decline 2 1500");
    assertThat(rows(o))
        .extracting(Payment::provider, Payment::state)
        .containsExactly(
            tuple("primary", PaymentState.VOIDED), tuple("secondary", PaymentState.DECLINED));
  }

  @Test
  void aLostAnswerAtTheSecondProviderIsFoundThereAndNotMadeAgain() {
    faults.set(
        "secondary.authorize", new FaultPlan(0, 0, 0, Duration.ZERO, List.of(Fault.UNKNOWN_AFTER)));
    faults.set(
        "secondary.capture", new FaultPlan(0, 0, 0, Duration.ZERO, List.of(Fault.UNKNOWN_AFTER)));
    Order o = order("acme", "EUR", tender("pm_giftcard_ok", 1000), tender("pm_card_ok", 1500));

    assertThat(drive(o).state()).isEqualTo(OrderState.COMPLETED);

    assertThat(ledger(secondary, o)).containsExactly("authorize 2 1500", "capture 2 1500");
    assertThat(ledger(primary, o)).containsExactly("authorize 1 1000", "capture 1 1000");
  }

  @Test
  void aSplitRefundReturnsEachShareThroughItsTendersProvider() {
    Order o = order("acme", "EUR", tender("pm_giftcard_ok", 1000), tender("pm_card_ok", 1500));
    assertThat(drive(o).state()).isEqualTo(OrderState.COMPLETED);
    RefundService service =
        new RefundService(
            orders,
            refunds,
            refundTransitions,
            contextRefundWorker,
            new RefundProperties(AllocationPolicy.REVERSE_ORDER),
            appClock);
    Refund r =
        new TransactionTemplate(txManager)
            .execute(s -> service.create("acme", o.id(), 2000, List.of()));
    assertThat(r.allocations())
        .extracting(a -> a.position(), a -> a.provider(), a -> a.amountMinor())
        .containsExactly(tuple(2, "secondary", 1500L), tuple(1, "primary", 500L));

    RefundWorker refundWorker =
        new RefundWorker(
            refunds,
            orders,
            refundTransitions,
            refundStep,
            props,
            AllocationPolicy.REVERSE_ORDER,
            clock,
            new Backoff(Duration.ofMillis(10), Duration.ofMillis(100), new SplittableRandom(1)),
            CrashPoints.NONE,
            new SimpleMeterRegistry());
    assertThat(refundWorker.runOnce()).isEqualTo(1);

    Refund done = refunds.findById(r.id()).orElseThrow();
    assertThat(done.state()).isEqualTo(RefundState.SUCCEEDED);
    List<Payment> rows = rows(o);
    assertThat(secondary.refundsFor(rows.get(1).providerPaymentId()))
        .extracting(
            PaymentProviderSimulator.Refund::reference, PaymentProviderSimulator.Refund::amount)
        .containsExactly(tuple(r.id().toString(), 1500L));
    assertThat(primary.refundsFor(rows.get(0).providerPaymentId()))
        .extracting(
            PaymentProviderSimulator.Refund::reference, PaymentProviderSimulator.Refund::amount)
        .containsExactly(tuple(r.id().toString(), 500L));
    assertThat(rows)
        .extracting(Payment::refundedMinor, Payment::state)
        .containsExactly(
            tuple(500L, PaymentState.PARTIALLY_REFUNDED), tuple(1500L, PaymentState.REFUNDED));
    assertThat(done.allocations())
        .extracting(a -> a.providerRefundId())
        .satisfiesExactly(
            id -> assertThat(id).startsWith("psp_rf_"), id -> assertThat(id).startsWith("re_"));
  }

  @Test
  void theDecisionIsStoredOnceAndTheDatabaseKeepsItWellFormed() {
    Order o = order("acme", "EUR", tender("pm_giftcard_ok", 1000), tender("pm_card_ok", 1500));
    UUID card = rows(o).get(1).id();
    assertThatThrownBy(
            () ->
                jdbc.sql("UPDATE payments SET routing_rule = 'Eur Cards' WHERE id = :id")
                    .param("id", card)
                    .update())
        .hasMessageContaining("payments_routing_rule_format");

    // An order inserted the way an older binary does gets its tender from the database's insert
    // trigger, which knows nothing of routing: the first provider by default, as before.
    Order older =
        Order.create(
            UUID.randomUUID(),
            "acme",
            null,
            CaptureMode.AUTOMATIC,
            Money.of(2500, "EUR"),
            List.of(new LineItem("sku-1", 2, 1000), new LineItem("sku-2", 1, 500)),
            Orders.ADDRESS,
            "pm_card_ok",
            "corr-older",
            clock.instant());
    assertThat(orders.insert(older)).isTrue();
    assertThat(rows(older))
        .extracting(Payment::provider, Payment::routingRule)
        .containsExactly(tuple("primary", "default"));
  }
}
