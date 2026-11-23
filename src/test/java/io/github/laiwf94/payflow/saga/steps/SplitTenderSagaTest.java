package io.github.laiwf94.payflow.saga.steps;

import static io.github.laiwf94.payflow.order.OrderState.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.laiwf94.payflow.money.Money;
import io.github.laiwf94.payflow.order.CaptureMode;
import io.github.laiwf94.payflow.order.LineItem;
import io.github.laiwf94.payflow.order.Order;
import io.github.laiwf94.payflow.order.OrderRepository;
import io.github.laiwf94.payflow.order.OrderTransitions;
import io.github.laiwf94.payflow.order.OrderTransitions.Tender;
import io.github.laiwf94.payflow.order.Orders;
import io.github.laiwf94.payflow.payment.Payment;
import io.github.laiwf94.payflow.payment.PaymentRepository;
import io.github.laiwf94.payflow.payment.PaymentState;
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
import io.github.laiwf94.payflow.support.Db;
import io.github.laiwf94.payflow.support.MutableClock;
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
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Orders paid with several tenders, end to end: the real payment steps, HTTP adapters, the
 * in-process provider simulator and Postgres. Each tender is authorized and captured under its own
 * key and reference, in position order, with its progress recorded between tenders, and undone last
 * tender first.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SplitTenderSagaTest {

  @Autowired OrderRepository repo;
  @Autowired OrderTransitions transitions;
  @Autowired PaymentRepository tenders;
  @Autowired List<SagaStep> steps;
  @Autowired SagaProperties props;
  @Autowired JdbcClient jdbc;
  @Autowired Faults faults;
  @Autowired PaymentProviderSimulator payments;
  @Autowired InventorySimulator inventory;
  @Autowired ShippingSimulator shipping;

  private final MutableClock clock = new MutableClock(Instant.now());
  private final Map<String, Consumer<String>> hooks = new HashMap<>();
  private SagaWorker worker;

  /** What the crash points throw: the worker's thread dies mid-step, its lease left behind. */
  static final class Crash extends Error {
    private static final long serialVersionUID = 1L;
  }

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
    worker =
        new SagaWorker(
            repo,
            transitions,
            steps,
            props,
            clock,
            new Backoff(Duration.ofMillis(10), Duration.ofMillis(100), new SplittableRandom(1)),
            crash,
            new SimpleMeterRegistry());
  }

  private Order order(CaptureMode mode, Tender... split) {
    Order o =
        Order.create(
            UUID.randomUUID(),
            "acme",
            null,
            mode,
            Money.of(2500, "EUR"),
            List.of(new LineItem("sku-1", 2, 1000), new LineItem("sku-2", 1, 500)),
            Orders.ADDRESS,
            split[0].method(),
            "corr-split",
            clock.instant());
    assertThat(transitions.create(o, List.of(split))).isTrue();
    return o;
  }

  private Order order(Tender... split) {
    return order(CaptureMode.AUTOMATIC, split);
  }

  private static Tender tender(String method, long amount) {
    return new Tender(method, Money.of(amount, "EUR"));
  }

  private Order drive(Order o) {
    for (int i = 0; i < 50; i++) {
      Order now = repo.findById(o.id()).orElseThrow();
      if (now.state().isTerminal()) {
        return now;
      }
      clock.advance(Duration.ofSeconds(5));
      worker.runOnce();
    }
    return repo.findById(o.id()).orElseThrow();
  }

  /** The provider's ledger for this order's tenders, in the order the calls were made. */
  private List<String> calls(Order o) {
    String id = o.id().toString();
    return payments.ledger().stream()
        .filter(e -> e.reference().equals(id) || e.reference().startsWith(id + "-"))
        .map(e -> e.operation() + " " + e.reference().substring(id.length()))
        .toList();
  }

  private List<Payment> rows(Order o) {
    return tenders.forOrder(o.id());
  }

  @Test
  void eachTenderIsAuthorizedAndCapturedUnderItsOwnKeyAndReference() {
    Order o = order(tender("pm_giftcard_ok", 1000), tender("pm_card_ok", 1500));
    List<Payment> created = rows(o);
    assertThat(created).extracting(Payment::position).containsExactly(1, 2);
    assertThat(created)
        .extracting(Payment::method, p -> p.amount().minorUnits(), Payment::state)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("pm_giftcard_ok", 1000L, PaymentState.PENDING),
            org.assertj.core.groups.Tuple.tuple("pm_card_ok", 1500L, PaymentState.PENDING));
    assertThat(created.get(0).authorizeKey()).isEqualTo(Keys.authorize(o.id()));
    assertThat(created.get(1).authorizeKey()).isEqualTo("authorize-" + o.id() + "-2");
    assertThat(created.get(1).captureKey()).isEqualTo("capture-" + o.id() + "-2");
    assertThat(created.get(1).providerReference()).isEqualTo(o.id() + "-2");

    worker.runOnce();

    Order done = repo.findById(o.id()).orElseThrow();
    assertThat(done.state()).isEqualTo(COMPLETED);
    assertThat(calls(o)).containsExactly("authorize ", "authorize -2", "capture ", "capture -2");
    assertThat(payments.ledger())
        .filteredOn(e -> e.operation().equals("capture"))
        .extracting(LedgerEntry::amount)
        .containsExactly(1000L, 1500L);
    List<Payment> after = rows(o);
    assertThat(after).extracting(Payment::state).containsOnly(PaymentState.CAPTURED);
    assertThat(after).extracting(Payment::capturedMinor).containsExactly(1000L, 1500L);
    assertThat(after)
        .extracting(Payment::providerPaymentId)
        .containsExactly("pay_00000001", "pay_00000002");
    assertThat(done.paymentId()).as("refunds go to the first tender").isEqualTo("pay_00000001");
    assertThat(done.capturedMinor()).isEqualTo(2500);
    assertThat(done.authorizationExpiresAt())
        .as("the earliest hold")
        .isEqualTo(
            after.stream()
                .map(Payment::authorizationExpiresAt)
                .min(Instant::compareTo)
                .orElseThrow());
  }

  @Test
  void aDeclineOnTheSecondTenderVoidsTheFirst() {
    Order o = order(tender("pm_giftcard_ok", 1000), tender("pm_card_declined", 1500));
    Order done = drive(o);
    assertThat(done.state()).isEqualTo(FAILED);
    assertThat(done.failureCode()).isEqualTo("payment_declined");
    assertThat(done.failureMessage()).isEqualTo("tender 2 declined: card_declined");
    assertThat(calls(o)).containsExactly("authorize ", "decline -2", "void ");
    assertThat(rows(o))
        .extracting(Payment::state, Payment::providerPaymentId)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(PaymentState.VOIDED, "pay_00000001"),
            org.assertj.core.groups.Tuple.tuple(PaymentState.DECLINED, null));
    assertThat(done.capturedMinor()).isZero();
    assertThat(inventory.heldFor("sku-1")).isZero();
  }

  @Test
  void aDeclineOnTheFirstTenderNeverTriesTheOthers() {
    Order o = order(tender("pm_card_expired", 1000), tender("pm_card_ok", 1500));
    Order done = drive(o);
    assertThat(done.state()).isEqualTo(FAILED);
    assertThat(done.failureMessage()).isEqualTo("tender 1 declined: expired_card");
    assertThat(calls(o)).containsExactly("decline ");
    assertThat(rows(o))
        .extracting(Payment::state)
        .containsExactly(PaymentState.DECLINED, PaymentState.CANCELED);
    assertThat(inventory.heldFor("sku-1")).isZero();
  }

  @Test
  void aHoldLostBeforeTheLastCaptureRefundsTheOthersLastFirst() {
    Order o =
        order(tender("pm_giftcard_ok", 500), tender("pm_card_ok", 800), tender("pm_card_ok", 1200));
    // The issuer drops the third hold after every tender is authorized, before any capture.
    hooks.put("before:PAYMENT_AUTHORIZED", p -> payments.expireNow("pay_00000003"));
    Order done = drive(o);
    assertThat(done.state()).isEqualTo(FAILED);
    assertThat(done.failureCode()).isEqualTo("authorization_expired");
    assertThat(calls(o))
        .containsExactly(
            "authorize ",
            "authorize -2",
            "authorize -3",
            "capture ",
            "capture -2",
            "refund -2",
            "refund ");
    assertThat(rows(o))
        .extracting(Payment::state, Payment::capturedMinor, Payment::refundedMinor)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(PaymentState.REFUNDED, 500L, 500L),
            org.assertj.core.groups.Tuple.tuple(PaymentState.REFUNDED, 800L, 800L),
            org.assertj.core.groups.Tuple.tuple(PaymentState.EXPIRED, 0L, 0L));
    assertThat(done.capturedMinor()).isEqualTo(1300);
    assertThat(done.refundedMinor()).isEqualTo(1300);
    assertThat(inventory.heldFor("sku-1")).isZero();
  }

  @Test
  void aCrashBetweenTendersResumesWithTheNextTender() {
    Order o = order(tender("pm_giftcard_ok", 1000), tender("pm_card_ok", 1500));
    // The first tender's progress commits; the worker dies before it calls for the second.
    hooks.put(
        "committed:INVENTORY_RESERVED",
        p -> {
          throw new Crash();
        });
    assertThatThrownBy(() -> worker.runOnce()).isInstanceOf(Crash.class);
    Order mid = repo.findById(o.id()).orElseThrow();
    assertThat(mid.state()).isEqualTo(INVENTORY_RESERVED);
    assertThat(mid.attempt()).as("progress is not an attempt").isZero();
    assertThat(mid.paymentId()).isEqualTo("pay_00000001");
    assertThat(rows(o))
        .extracting(Payment::state)
        .containsExactly(PaymentState.AUTHORIZED, PaymentState.PENDING);

    clock.advance(props.lease().plusSeconds(1));
    worker.runOnce();
    assertThat(repo.findById(o.id()).orElseThrow().state()).isEqualTo(COMPLETED);
    assertThat(calls(o))
        .as("the resumed step asks about the second tender only, and finds nothing")
        .containsExactly("authorize ", "authorize -2", "capture ", "capture -2");
  }

  @Test
  void aCrashAfterTheSecondAuthorizationFindsItInsteadOfAuthorizingAgain() {
    Order o = order(tender("pm_giftcard_ok", 1000), tender("pm_card_ok", 1500));
    // The second authorization is made, then the worker dies before recording it.
    hooks.put(
        "committed:INVENTORY_RESERVED",
        p ->
            hooks.put(
                "after:INVENTORY_RESERVED",
                q -> {
                  throw new Crash();
                }));
    assertThatThrownBy(() -> worker.runOnce()).isInstanceOf(Crash.class);
    assertThat(rows(o))
        .extracting(Payment::state)
        .containsExactly(PaymentState.AUTHORIZED, PaymentState.PENDING);

    clock.advance(props.lease().plusSeconds(1));
    worker.runOnce();
    assertThat(repo.findById(o.id()).orElseThrow().state()).isEqualTo(COMPLETED);
    assertThat(calls(o)).containsExactly("authorize ", "authorize -2", "capture ", "capture -2");
    assertThat(rows(o))
        .extracting(Payment::providerPaymentId)
        .containsExactly("pay_00000001", "pay_00000002");
  }

  @Test
  void aLostCaptureAnswerOnTheSecondTenderIsFoundAndNotRepeated() {
    faults.set(
        "payment.capture",
        new FaultPlan(0, 0, 0, Duration.ZERO, List.of(Fault.OK, Fault.UNKNOWN_AFTER)));
    Order o = order(tender("pm_giftcard_ok", 1000), tender("pm_card_ok", 1500));
    Order done = drive(o);
    assertThat(done.state()).isEqualTo(COMPLETED);
    assertThat(calls(o)).containsExactly("authorize ", "authorize -2", "capture ", "capture -2");
    assertThat(rows(o)).extracting(Payment::capturedMinor).containsExactly(1000L, 1500L);
  }

  @Test
  void aCancelBetweenTendersVoidsBothLastFirst() {
    Order o = order(tender("pm_giftcard_ok", 1000), tender("pm_card_ok", 1500));
    hooks.put(
        "committed:INVENTORY_RESERVED",
        p -> assertThat(transitions.requestCancel("acme", o.id(), clock.instant())).isPresent());
    Order done = drive(o);
    assertThat(done.state()).isEqualTo(CANCELLED);
    assertThat(calls(o)).containsExactly("authorize ", "authorize -2", "void -2", "void ");
    assertThat(rows(o))
        .extracting(Payment::state)
        .containsExactly(PaymentState.VOIDED, PaymentState.VOIDED);
    assertThat(inventory.heldFor("sku-1")).isZero();
  }

  @Test
  void aShipmentThatFailsRefundsEveryTenderLastFirst() {
    faults.set(
        "shipping.create",
        new FaultPlan(0, 0, 0, Duration.ZERO, java.util.Collections.nCopies(30, Fault.ERROR)));
    Order o = order(tender("pm_giftcard_ok", 1000), tender("pm_card_ok", 1500));
    Order done = drive(o);
    assertThat(done.state()).isEqualTo(FAILED);
    assertThat(done.failureCode()).isEqualTo("step_timeout");
    assertThat(calls(o))
        .containsExactly(
            "authorize ", "authorize -2", "capture ", "capture -2", "refund -2", "refund ");
    assertThat(rows(o)).extracting(Payment::state).containsOnly(PaymentState.REFUNDED);
    assertThat(done.refundedMinor()).isEqualTo(2500);
  }

  @Test
  void aManualOrderExtendsAndCapturesEveryTender() {
    Order o = order(CaptureMode.MANUAL, tender("pm_giftcard_ok", 1000), tender("pm_card_ok", 1500));
    worker.runOnce();
    Order waiting = repo.findById(o.id()).orElseThrow();
    assertThat(waiting.state()).isEqualTo(PAYMENT_AUTHORIZED);
    Instant firstExpiry = waiting.authorizationExpiresAt();

    clock.advance(Duration.ofSeconds(1));
    assertThat(repo.requestReauthorization(o.id(), clock.instant())).isPresent();
    worker.runOnce();
    Order extended = repo.findById(o.id()).orElseThrow();
    assertThat(extended.state()).isEqualTo(PAYMENT_AUTHORIZED);
    assertThat(extended.reauthorizedAt()).isNotNull();
    List<Payment> rows = rows(o);
    assertThat(rows).extracting(Payment::reauthorizedAt).doesNotContainNull();
    assertThat(extended.authorizationExpiresAt())
        .isEqualTo(rows.stream().map(Payment::authorizationExpiresAt).min(Instant::compareTo).get())
        .isAfterOrEqualTo(firstExpiry);
    assertThat(calls(o))
        .containsExactly("authorize ", "authorize -2", "reauthorize ", "reauthorize -2");

    assertThat(transitions.requestCapture("acme", o.id(), clock.instant())).isPresent();
    worker.runOnce();
    assertThat(repo.findById(o.id()).orElseThrow().state()).isEqualTo(COMPLETED);
    assertThat(calls(o))
        .containsExactly(
            "authorize ",
            "authorize -2",
            "reauthorize ",
            "reauthorize -2",
            "capture ",
            "capture -2");
  }

  @Test
  void tendersMustAddUpToTheOrder() {
    Order o =
        Order.create(
            UUID.randomUUID(),
            "acme",
            null,
            CaptureMode.AUTOMATIC,
            Money.of(2500, "EUR"),
            List.of(new LineItem("sku-1", 2, 1000), new LineItem("sku-2", 1, 500)),
            Orders.ADDRESS,
            "pm_card_ok",
            "corr-split",
            clock.instant());
    assertThatThrownBy(() -> transitions.create(o, List.of(tender("a", 1000), tender("b", 1000))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("add up");
    assertThatThrownBy(
            () ->
                transitions.create(
                    o, List.of(tender("a", 1000), new Tender("b", Money.of(1500, "USD")))))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(() -> transitions.create(o, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                transitions.create(
                    o, java.util.Collections.nCopies(17, tender("a", 2500 / 17 + 1))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("1 to 16");
    assertThat(repo.findById(o.id())).as("nothing was written").isEmpty();
  }

  @Test
  void anOrderFailedBeforePaymentCancelsItsTenders() {
    inventory.setStock("sku-2", 0);
    Order o = order(tender("pm_giftcard_ok", 1000), tender("pm_card_ok", 1500));
    Order done = drive(o);
    assertThat(done.state()).isEqualTo(FAILED);
    assertThat(done.failureCode()).isEqualTo("out_of_stock");
    assertThat(calls(o)).isEmpty();
    assertThat(rows(o))
        .extracting(Payment::state)
        .containsExactly(PaymentState.CANCELED, PaymentState.CANCELED);
  }

  @Test
  void onlyWritesThatDoNotSayTheyOwnTheTendersAreMirrored() {
    Order o = order(tender("pm_card_ok", 2500));
    String update =
        "UPDATE orders SET state = 'RELEASING_INVENTORY', failure_code = 'x' WHERE id = :id";
    // This binary's writes say so in the statement, and the tender is left as the steps wrote it.
    jdbc.sql(update + " AND set_config('payflow.payment_writer', 'tenders', true) = 'tenders'")
        .param("id", o.id())
        .update();
    assertThat(rows(o)).extracting(Payment::state).containsExactly(PaymentState.PENDING);
    // An older binary's write of the same row is mirrored, as before.
    jdbc.sql("UPDATE orders SET failure_code = 'y' WHERE id = :id").param("id", o.id()).update();
    assertThat(rows(o)).extracting(Payment::state).containsExactly(PaymentState.CANCELED);
  }

  @Test
  void theMirrorForOlderBinariesLeavesStepWrittenTendersAlone() {
    Order o = order(tender("pm_giftcard_ok", 1000), tender("pm_card_ok", 1500));
    // An older binary writes only the order row; with two tenders, the mirror must not fold the
    // order's totals into the first.
    jdbc.sql("UPDATE orders SET state = 'RELEASING_INVENTORY', failure_code = 'x' WHERE id = :id")
        .param("id", o.id())
        .update();
    assertThat(rows(o))
        .extracting(Payment::state, p -> p.amount().minorUnits())
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(PaymentState.PENDING, 1000L),
            org.assertj.core.groups.Tuple.tuple(PaymentState.PENDING, 1500L));
  }
}
