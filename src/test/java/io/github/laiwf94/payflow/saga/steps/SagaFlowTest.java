package io.github.laiwf94.payflow.saga.steps;

import static io.github.laiwf94.payflow.order.OrderState.*;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.laiwf94.payflow.money.Money;
import io.github.laiwf94.payflow.order.LineItem;
import io.github.laiwf94.payflow.order.Order;
import io.github.laiwf94.payflow.order.OrderRepository;
import io.github.laiwf94.payflow.order.OrderState;
import io.github.laiwf94.payflow.order.OrderTransitions;
import io.github.laiwf94.payflow.order.Orders;
import io.github.laiwf94.payflow.order.ShippingAddress;
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

/** Every saga path end to end: real steps, HTTP adapters, in-process simulators, Postgres. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SagaFlowTest {

  @Autowired OrderRepository repo;
  @Autowired OrderTransitions transitions;
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

  @BeforeEach
  void setUp() {
    Db.truncateAll(jdbc);
    faults.reset(new SplittableRandom(1));
    payments.reset();
    inventory.reset();
    shipping.reset();
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
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
  }

  private Order insert(String method, ShippingAddress address) {
    Order o =
        Order.create(
            UUID.randomUUID(),
            "acme",
            Money.of(2500, "EUR"),
            List.of(new LineItem("sku-1", 2, 1000), new LineItem("sku-2", 1, 500)),
            address,
            method,
            "corr-test",
            clock.instant());
    repo.insert(o);
    return o;
  }

  private Order insert() {
    return insert("pm_card_ok", Orders.ADDRESS);
  }

  /** Runs the worker, moving the clock past any backoff, until the order stops changing. */
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

  private List<String> operations(Order o) {
    return payments.ledgerFor(o.id().toString()).stream().map(LedgerEntry::operation).toList();
  }

  @Test
  void happyPath() {
    Order o = insert();
    worker.runOnce();
    Order done = repo.findById(o.id()).orElseThrow();
    assertThat(done.state()).isEqualTo(COMPLETED);
    assertThat(done.capturedMinor()).isEqualTo(2500);
    assertThat(done.reservationId()).isEqualTo(o.id().toString());
    assertThat(done.paymentId()).isEqualTo("pay_00000001");
    assertThat(done.trackingNumber()).startsWith("TRK");
    assertThat(operations(o)).containsExactly("authorize", "capture");
    assertThat(inventory.heldFor("sku-1")).isEqualTo(2);
    assertThat(shipping.shipment(o.id().toString())).isPresent();
  }

  @Test
  void declinedCardReleasesStock() {
    Order o = insert("pm_card_insufficient_funds", Orders.ADDRESS);
    Order done = drive(o);
    assertThat(done.state()).isEqualTo(FAILED);
    assertThat(done.failureCode()).isEqualTo("payment_declined");
    assertThat(done.failureMessage()).isEqualTo("card declined: insufficient_funds");
    assertThat(operations(o)).containsExactly("decline");
    assertThat(inventory.heldFor("sku-1")).isZero();
  }

  @Test
  void outOfStockFailsWithoutTouchingThePayment() {
    inventory.setStock("sku-2", 0);
    Order o = insert();
    Order done = drive(o);
    assertThat(done.state()).isEqualTo(FAILED);
    assertThat(done.failureCode()).isEqualTo("out_of_stock");
    assertThat(operations(o)).isEmpty();
    assertThat(inventory.stock("sku-1")).isEqualTo(inventory.initialStock("sku-1"));
  }

  @Test
  void refusedCaptureVoidsNothingTwiceAndReleasesStock() {
    Order o = insert();
    // The authorization expires at the provider between authorize and capture.
    hooks.put(
        "committed:INVENTORY_RESERVED", p -> payments.voidAuthorization(null, "pay_00000001"));
    Order done = drive(o);
    assertThat(done.state()).isEqualTo(FAILED);
    assertThat(done.failureCode()).isEqualTo("capture_failed");
    assertThat(operations(o)).containsExactly("authorize", "void");
    assertThat(inventory.heldFor("sku-1")).isZero();
  }

  @Test
  void undeliverableAddressRefundsTheCapture() {
    Order o =
        insert(
            "pm_card_ok",
            new ShippingAddress("Bob Example", "1 Road", null, "Town", "00000", "NL"));
    Order done = drive(o);
    assertThat(done.state()).isEqualTo(FAILED);
    assertThat(done.failureCode()).isEqualTo("shipping_failed");
    assertThat(done.capturedMinor()).isEqualTo(2500);
    assertThat(done.refundedMinor()).isEqualTo(2500);
    assertThat(operations(o)).containsExactly("authorize", "capture", "refund");
    assertThat(inventory.heldFor("sku-1")).isZero();
  }

  @Test
  void cancelBeforeAnythingHappened() {
    Order o = insert();
    assertThat(transitions.requestCancel("acme", o.id(), clock.instant())).isPresent();
    Order done = drive(o);
    assertThat(done.state()).isEqualTo(CANCELLED);
    assertThat(done.failureCode()).isNull();
    assertThat(operations(o)).isEmpty();
  }

  @Test
  void cancelAfterReservationVoidsAnyAuthorization() {
    Order o = insert();
    hooks.put("committed:PENDING", p -> transitions.requestCancel("acme", o.id(), clock.instant()));
    Order done = drive(o);
    assertThat(done.state()).isEqualTo(CANCELLED);
    assertThat(inventory.heldFor("sku-1")).isZero();
    assertThat(payments.paymentsFor(o.id().toString()))
        .allMatch(p -> p.status() == PaymentProviderSimulator.Status.VOIDED);
  }

  @Test
  void cancelWhileCaptureIsInFlightRefunds() {
    Order o = insert();
    hooks.put(
        "committed:INVENTORY_RESERVED",
        p -> transitions.requestCancel("acme", o.id(), clock.instant()));
    Order done = drive(o);
    assertThat(done.state()).isEqualTo(CANCELLED);
    assertThat(done.refundedMinor()).isEqualTo(done.capturedMinor());
    assertThat(shipping.shipment(o.id().toString())).isEmpty();
  }

  @Test
  void cancelIsRefusedOnceCapturedOrForAnotherMerchant() {
    Order o = insert();
    assertThat(transitions.requestCancel("globex", o.id(), clock.instant())).isEmpty();
    worker.runOnce();
    assertThat(transitions.requestCancel("acme", o.id(), clock.instant())).isEmpty();
  }

  @Test
  void refundAfterCompletion() {
    Order o = insert();
    worker.runOnce();
    assertThat(transitions.requestRefund("globex", o.id(), clock.instant())).isEmpty();
    assertThat(transitions.requestRefund("acme", o.id(), clock.instant()))
        .get()
        .extracting(Order::state)
        .isEqualTo(REFUNDING);
    assertThat(transitions.requestRefund("acme", o.id(), clock.instant())).isEmpty();
    Order done = drive(o);
    assertThat(done.state()).isEqualTo(REFUNDED);
    assertThat(done.refundedMinor()).isEqualTo(2500);
    assertThat(operations(o)).containsExactly("authorize", "capture", "refund");
    assertThat(inventory.heldFor("sku-1")).as("goods were shipped").isEqualTo(2);
  }

  @Test
  void transientFailuresAreRetried() {
    faults.set(
        "inventory.reserve",
        new FaultPlan(0, 0, 0, Duration.ZERO, List.of(Fault.ERROR, Fault.ERROR)));
    faults.set("shipping.create", new FaultPlan(0, 0, 0, Duration.ZERO, List.of(Fault.ERROR)));
    Order o = insert();
    worker.runOnce();
    assertThat(repo.findById(o.id()).orElseThrow().attempt()).isEqualTo(1);
    assertThat(drive(o).state()).isEqualTo(COMPLETED);
  }

  @Test
  void aShipmentWhoseAnswerWasLostIsCancelledWhenTheStepGivesUp() {
    List<Fault> script = new java.util.ArrayList<>(List.of(Fault.UNKNOWN_AFTER));
    script.addAll(java.util.Collections.nCopies(20, Fault.ERROR));
    faults.set("shipping.create", new FaultPlan(0, 0, 0, Duration.ZERO, script));
    Order o = insert();
    Order done = drive(o);
    assertThat(done.state()).isEqualTo(FAILED);
    assertThat(done.failureCode()).isEqualTo("step_timeout");
    assertThat(shipping.shipment(o.id().toString()))
        .get()
        .extracting(ShippingSimulator.Shipment::status)
        .isEqualTo(ShippingSimulator.Status.CANCELLED);
    assertThat(done.refundedMinor()).isEqualTo(2500);
  }

  @Test
  void compensationsAreSafeToRunTwice() {
    Order o = insert();
    worker.runOnce();
    Order done = repo.findById(o.id()).orElseThrow();
    for (OrderState s : List.of(CANCELLING_SHIPMENT, REVERSING_PAYMENT, RELEASING_INVENTORY)) {
      SagaStep step = steps.stream().filter(x -> x.handles() == s).findFirst().orElseThrow();
      step.execute(done, true);
      List<String> afterFirst = operations(o);
      var shipmentAfterFirst = shipping.shipment(o.id().toString());
      long heldAfterFirst = inventory.heldFor("sku-1");
      step.execute(done, true);
      assertThat(operations(o)).as(s.name()).isEqualTo(afterFirst);
      assertThat(shipping.shipment(o.id().toString())).isEqualTo(shipmentAfterFirst);
      assertThat(inventory.heldFor("sku-1")).isEqualTo(heldAfterFirst);
    }
    assertThat(operations(o)).containsExactly("authorize", "capture", "refund");
  }
}
