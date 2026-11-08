package io.github.laiwf94.payflow.saga.steps;

import static io.github.laiwf94.payflow.order.OrderState.*;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.laiwf94.payflow.order.Order;
import io.github.laiwf94.payflow.order.OrderRepository;
import io.github.laiwf94.payflow.order.OrderTransitions;
import io.github.laiwf94.payflow.order.Orders;
import io.github.laiwf94.payflow.order.ShippingAddress;
import io.github.laiwf94.payflow.refund.RefundLine;
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
import io.github.laiwf94.payflow.sim.SimulatorSettings;
import io.github.laiwf94.payflow.support.Db;
import io.github.laiwf94.payflow.support.MutableClock;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.SplittableRandom;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Every money-moving call is applied by the provider and then its answer is lost, over real HTTP
 * with real read timeouts. Each case runs with the provider honouring idempotency keys and with it
 * ignoring them: the orchestrator must never move money twice either way.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "payflow.simulator.answer-delay=600ms",
      "payflow.dependencies.payment.read-timeout=300ms",
      "payflow.dependencies.inventory.read-timeout=300ms"
    })
class UnknownOutcomeTest {

  @Autowired OrderRepository repo;
  @Autowired OrderTransitions transitions;
  @Autowired List<SagaStep> steps;
  @Autowired SagaProperties props;
  @Autowired JdbcClient jdbc;
  @Autowired Faults faults;
  @Autowired SimulatorSettings settings;
  @Autowired PaymentProviderSimulator payments;
  @Autowired InventorySimulator inventory;
  @Autowired ShippingSimulator shipping;
  @Autowired MeterRegistry meters;
  @Autowired RefundService refundService;
  @Autowired RefundRepository refunds;
  @Autowired RefundTransitions refundTransitions;
  @Autowired RefundStep refundStep;

  private final MutableClock clock = new MutableClock(Instant.now());
  private SagaWorker worker;
  private RefundWorker refundWorker;

  @BeforeEach
  void setUp() {
    Db.truncateAll(jdbc);
    faults.reset(new SplittableRandom(1));
    payments.reset();
    inventory.reset();
    shipping.reset();
    worker =
        new SagaWorker(
            repo,
            transitions,
            steps,
            props,
            clock,
            new Backoff(Duration.ofMillis(10), Duration.ofMillis(100), new SplittableRandom(1)),
            CrashPoints.NONE,
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    refundWorker =
        new RefundWorker(
            refunds,
            repo,
            refundTransitions,
            refundStep,
            props,
            clock,
            new Backoff(Duration.ofMillis(10), Duration.ofMillis(100), new SplittableRandom(1)),
            CrashPoints.NONE,
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
  }

  private void lose(String operation, Fault... script) {
    faults.set(operation, new FaultPlan(0, 0, 0, Duration.ZERO, List.of(script)));
  }

  private Order insert(String method, ShippingAddress address) {
    Order base = Orders.pending("acme", clock.instant());
    Order o =
        Order.create(
            UUID.randomUUID(),
            "acme",
            base.amount(),
            base.items(),
            address,
            method,
            "corr",
            clock.instant());
    repo.insert(o);
    return o;
  }

  private Order drive(Order o) {
    for (int i = 0; i < 40; i++) {
      Order now = repo.findById(o.id()).orElseThrow();
      if (now.state().isTerminal()
          && refunds.listForOrder(o.id()).stream().allMatch(r -> r.state().isTerminal())) {
        return now;
      }
      clock.advance(Duration.ofSeconds(5));
      worker.runOnce();
      refundWorker.runOnce();
    }
    return repo.findById(o.id()).orElseThrow();
  }

  private long count(Order o, String operation) {
    return payments.ledgerFor(o.id().toString()).stream()
        .map(LedgerEntry::operation)
        .filter(operation::equals)
        .count();
  }

  private double counter(String name, String... tags) {
    var c = meters.find(name).tags(tags).counter();
    return c == null ? 0 : c.count();
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void lostAuthorizationAnswerIsFoundNotRepeated(boolean honorKeys) {
    settings.honorIdempotencyKeys(honorKeys);
    double before =
        counter(
            "payflow.payment.unknown.resolved", "operation", "authorize", "resolution", "applied");
    lose("payment.authorize", Fault.UNKNOWN_AFTER);
    Order o = insert("pm_card_ok", Orders.ADDRESS);
    Order done = drive(o);
    assertThat(done.state()).isEqualTo(COMPLETED);
    assertThat(count(o, "authorize")).isEqualTo(1);
    assertThat(count(o, "capture")).isEqualTo(1);
    assertThat(
            counter(
                "payflow.payment.unknown.resolved",
                "operation",
                "authorize",
                "resolution",
                "applied"))
        .isEqualTo(before + 1);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void lostAuthorizationRequestIsRetried(boolean honorKeys) {
    settings.honorIdempotencyKeys(honorKeys);
    lose("payment.authorize", Fault.UNKNOWN_BEFORE, Fault.UNKNOWN_BEFORE);
    Order o = insert("pm_card_ok", Orders.ADDRESS);
    assertThat(drive(o).state()).isEqualTo(COMPLETED);
    assertThat(count(o, "authorize")).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void lostDeclineIsFoundAndFailsTheOrder(boolean honorKeys) {
    settings.honorIdempotencyKeys(honorKeys);
    lose("payment.authorize", Fault.UNKNOWN_AFTER);
    Order o = insert("pm_card_declined", Orders.ADDRESS);
    Order done = drive(o);
    assertThat(done.state()).isEqualTo(FAILED);
    assertThat(done.failureCode()).isEqualTo("payment_declined");
    assertThat(count(o, "decline")).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void lostCaptureAnswerCompletesWithOneCapture(boolean honorKeys) {
    settings.honorIdempotencyKeys(honorKeys);
    lose("payment.capture", Fault.UNKNOWN_AFTER);
    lose("payment.lookup", Fault.ERROR);
    Order o = insert("pm_card_ok", Orders.ADDRESS);
    Order done = drive(o);
    assertThat(done.state()).isEqualTo(COMPLETED);
    assertThat(count(o, "capture")).isEqualTo(1);
    assertThat(done.capturedMinor()).isEqualTo(2500);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void lostRefundDuringCompensationIsNotRepeated(boolean honorKeys) {
    settings.honorIdempotencyKeys(honorKeys);
    lose("payment.refund", Fault.UNKNOWN_AFTER);
    Order o =
        insert(
            "pm_card_ok",
            new ShippingAddress("Bob Example", "1 Road", null, "Town", "00000", "NL"));
    Order done = drive(o);
    assertThat(done.state()).isEqualTo(FAILED);
    assertThat(count(o, "refund")).isEqualTo(1);
    assertThat(done.refundedMinor()).isEqualTo(done.capturedMinor()).isEqualTo(2500);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void lostCustomerRefundIsNotRepeated(boolean honorKeys) {
    settings.honorIdempotencyKeys(honorKeys);
    Order o = insert("pm_card_ok", Orders.ADDRESS);
    drive(o);
    lose("payment.refund", Fault.UNKNOWN_AFTER);
    refundService.refundRemaining("acme", o.id());
    Order done = drive(o);
    assertThat(done.state()).isEqualTo(REFUNDED);
    assertThat(done.refundedMinor()).isEqualTo(2500);
    assertThat(count(o, "refund")).isEqualTo(1);
    assertThat(refunds.listForOrder(o.id()))
        .singleElement()
        .satisfies(
            r -> {
              assertThat(r.state()).isEqualTo(RefundState.SUCCEEDED);
              assertThat(r.attempt()).as("found on the retry, not made again").isEqualTo(1);
            });
  }

  /**
   * One mug of two refunded by line, with the given restock faults: the money goes back once, the
   * mug goes back to stock once, and the refund settles on attempt {@code attempts - 1}.
   */
  private void lineRefundSettlesOnce(boolean honorKeys, int attempts) {
    settings.honorIdempotencyKeys(honorKeys);
    Order o = insert("pm_card_ok", Orders.ADDRESS);
    assertThat(drive(o).state()).isEqualTo(COMPLETED);
    long shelf = inventory.stock("sku-1");
    assertThat(inventory.heldFor("sku-1")).isEqualTo(2);

    refundService.refundLines("acme", o.id(), List.of(new RefundLine("sku-1", 1)));
    Order done = drive(o);
    assertThat(done.state()).isEqualTo(PARTIALLY_REFUNDED);
    assertThat(done.refundedMinor()).isEqualTo(1000);
    assertThat(count(o, "refund")).as("the money went back once").isEqualTo(1);
    assertThat(inventory.stock("sku-1")).as("the mug went back once").isEqualTo(shelf + 1);
    assertThat(inventory.heldFor("sku-1")).isEqualTo(1);
    assertThat(inventory.heldFor("sku-2")).as("the other line is untouched").isEqualTo(1);
    assertThat(inventory.stock("sku-1") + inventory.heldFor("sku-1"))
        .isEqualTo(inventory.initialStock("sku-1"));
    var refund = refunds.listForOrder(o.id()).get(0);
    assertThat(refund.state()).isEqualTo(RefundState.SUCCEEDED);
    assertThat(refund.attempt()).isEqualTo(attempts - 1);
    assertThat(inventory.restocksOf(o.id().toString()))
        .singleElement()
        .satisfies(
            x -> {
              assertThat(x.id())
                  .as("restocked under the refund's id")
                  .isEqualTo(refund.id().toString());
              assertThat(x.lines()).containsExactly(new InventorySimulator.Line("sku-1", 1));
            });
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void lostRestockAnswerReturnsTheStockOnce(boolean honorKeys) {
    lose("inventory.restock", Fault.UNKNOWN_AFTER);
    lineRefundSettlesOnce(honorKeys, 2);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void lostRestockRequestIsRetried(boolean honorKeys) {
    lose("inventory.restock", Fault.UNKNOWN_BEFORE, Fault.ERROR);
    lineRefundSettlesOnce(honorKeys, 3);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void lostLineRefundAnswerRestocksAfterTheMoneyIsFound(boolean honorKeys) {
    lose("payment.refund", Fault.UNKNOWN_AFTER);
    lose("inventory.restock", Fault.UNKNOWN_AFTER);
    lineRefundSettlesOnce(honorKeys, 3);
  }

  @Test
  void aRefusedRestockKeepsTheRefundPendingWithoutRefundingTwice() {
    settings.honorIdempotencyKeys(false);
    Order o = insert("pm_card_ok", Orders.ADDRESS);
    assertThat(drive(o).state()).isEqualTo(COMPLETED);
    // The inventory system lost the reservation, so it refuses every restock from it.
    inventory.reset();
    refundService.refundLines("acme", o.id(), List.of(new RefundLine("sku-2", 1)));
    for (int i = 0; i < 4; i++) {
      clock.advance(Duration.ofSeconds(5));
      refundWorker.runOnce();
    }
    var refund = refunds.listForOrder(o.id()).get(0);
    assertThat(refund.state()).as("the stock is still owed").isEqualTo(RefundState.PENDING);
    assertThat(refund.attempt()).isEqualTo(4);
    assertThat(count(o, "refund"))
        .as("each retry finds the money it already returned")
        .isEqualTo(1);
    assertThat(repo.findById(o.id()).orElseThrow().state()).isEqualTo(COMPLETED);
    assertThat(inventory.stock("sku-2")).isEqualTo(inventory.initialStock("sku-2"));
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void lostVoidIsNotRepeated(boolean honorKeys) {
    settings.honorIdempotencyKeys(honorKeys);
    lose("payment.void", Fault.UNKNOWN_AFTER);
    Order o = insert("pm_card_ok", Orders.ADDRESS);
    // The cancel lands after authorization, so the hold has to be voided.
    lose("payment.capture", Fault.ERROR);
    clock.advance(Duration.ofSeconds(1));
    worker.runOnce();
    assertThat(repo.findById(o.id()).orElseThrow().state()).isEqualTo(PAYMENT_AUTHORIZED);
    transitions.requestCancel("acme", o.id(), clock.instant());
    Order done = drive(o);
    assertThat(done.state()).isEqualTo(CANCELLED);
    assertThat(count(o, "void")).isEqualTo(1);
    assertThat(count(o, "capture")).isZero();
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void resumedCaptureOfAVoidedHoldFailsTheOrder(boolean honorKeys) {
    settings.honorIdempotencyKeys(honorKeys);
    lose("payment.capture", Fault.UNKNOWN_BEFORE);
    Order o = insert("pm_card_ok", Orders.ADDRESS);
    worker.runOnce();
    payments.voidAuthorization(null, repo.findById(o.id()).orElseThrow().paymentId());
    Order done = drive(o);
    assertThat(done.state()).isEqualTo(FAILED);
    assertThat(done.failureMessage()).isEqualTo("provider refused the capture: voided");
    assertThat(count(o, "capture")).isZero();
  }
}
