package io.github.laiwf94.payflow.refund;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

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
import io.github.laiwf94.payflow.saga.Backoff;
import io.github.laiwf94.payflow.saga.CrashPoints;
import io.github.laiwf94.payflow.saga.SagaProperties;
import io.github.laiwf94.payflow.saga.SagaStep;
import io.github.laiwf94.payflow.saga.SagaWorker;
import io.github.laiwf94.payflow.sim.Faults;
import io.github.laiwf94.payflow.sim.InventorySimulator;
import io.github.laiwf94.payflow.sim.PaymentProviderSimulator;
import io.github.laiwf94.payflow.sim.ShippingSimulator;
import io.github.laiwf94.payflow.sim.SimulatorSettings;
import io.github.laiwf94.payflow.support.Db;
import io.github.laiwf94.payflow.support.MutableClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.function.Consumer;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Refunds of orders paid with several tenders, end to end: the service splitting each refund by its
 * policy under the order's lock, the worker making each tender's share at that tender's provider
 * payment and recording it before the next, partial failures, crashes between shares, the
 * database's per-tender bounds, and refunds an older binary created or settled.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SplitTenderRefundTest {

  @Autowired OrderRepository orders;
  @Autowired OrderTransitions orderTransitions;
  @Autowired PaymentRepository tenders;
  @Autowired List<SagaStep> steps;
  @Autowired SagaProperties props;
  @Autowired JdbcClient jdbc;
  @Autowired Faults faults;
  @Autowired SimulatorSettings settings;
  @Autowired PaymentProviderSimulator payments;
  @Autowired InventorySimulator inventory;
  @Autowired ShippingSimulator shipping;
  @Autowired RefundRepository refunds;
  @Autowired RefundTransitions transitions;
  @Autowired RefundAttempt step;
  @Autowired RefundWorker contextWorker;
  @Autowired Clock appClock;
  @Autowired PlatformTransactionManager txManager;

  // Refunds are created on the application's clock; the workers' clock runs ahead of it, so a new
  // refund is always due for them.
  private final MutableClock clock = new MutableClock(Instant.now().plus(Duration.ofHours(1)));
  private final Map<String, Consumer<String>> hooks = new HashMap<>();
  private RefundWorker worker;

  /** What the crash points throw: the worker's thread dies, its lease left behind. */
  static final class Crash extends Error {
    private static final long serialVersionUID = 1L;
  }

  @BeforeEach
  void setUp() {
    Db.truncateAll(jdbc);
    faults.reset(new SplittableRandom(1));
    settings.honorIdempotencyKeys(true);
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
        new RefundWorker(
            refunds,
            orders,
            transitions,
            step,
            props,
            AllocationPolicy.REVERSE_ORDER,
            clock,
            new Backoff(Duration.ofMillis(10), Duration.ofMillis(100), new SplittableRandom(1)),
            crash,
            new SimpleMeterRegistry());
  }

  @AfterEach
  void reset() {
    settings.honorIdempotencyKeys(true);
  }

  /**
   * The refund service splitting by {@code policy}, each call in its own transaction as the
   * application's proxied one runs it.
   */
  private Service service(AllocationPolicy policy) {
    return new Service(
        new RefundService(
            orders, refunds, transitions, contextWorker, new RefundProperties(policy), appClock),
        new TransactionTemplate(txManager));
  }

  record Service(RefundService service, TransactionTemplate tx) {
    Refund create(String merchant, UUID order, long amount, List<RefundLine> lines) {
      return tx.execute(s -> service.create(merchant, order, amount, lines));
    }

    Refund refundLines(String merchant, UUID order, List<RefundLine> lines) {
      return tx.execute(s -> service.refundLines(merchant, order, lines));
    }
  }

  private static Tender tender(String method, long amount) {
    return new Tender(method, Money.of(amount, "EUR"));
  }

  /**
   * A 25.00 order (two sku-1 at 10.00, one sku-2 at 5.00) paid with {@code split} and taken through
   * its saga.
   */
  private Order completed(Tender... split) {
    Order o =
        Order.create(
            UUID.randomUUID(),
            "acme",
            null,
            CaptureMode.AUTOMATIC,
            Money.of(2500, "EUR"),
            List.of(new LineItem("sku-1", 2, 1000), new LineItem("sku-2", 1, 500)),
            Orders.ADDRESS,
            split[0].method(),
            "corr-split-refund",
            clock.instant());
    assertThat(orderTransitions.create(o, List.of(split))).isTrue();
    new SagaWorker(
            orders,
            orderTransitions,
            steps,
            props,
            clock,
            new Backoff(Duration.ofMillis(10), Duration.ofMillis(100), new SplittableRandom(1)),
            CrashPoints.NONE,
            new SimpleMeterRegistry())
        .runOnce();
    Order done = orders.findById(o.id()).orElseThrow();
    assertThat(done.state()).isEqualTo(OrderState.COMPLETED);
    return done;
  }

  /** Gift card 10.00, then card 15.00. */
  private Order giftThenCard() {
    return completed(tender("pm_giftcard_ok", 1000), tender("pm_card_ok", 1500));
  }

  private Order order(Order o) {
    return orders.findById(o.id()).orElseThrow();
  }

  private Refund refund(Refund r) {
    return refunds.findById(r.id()).orElseThrow();
  }

  private List<Payment> rows(Order o) {
    return tenders.forOrder(o.id());
  }

  /** The provider's refunds of the tender at {@code position}, as {@code reference:amount}. */
  private List<String> providerRefunds(Order o, int position) {
    return payments.refundsFor(rows(o).get(position - 1).providerPaymentId()).stream()
        .map(r -> r.reference() + ":" + r.amount())
        .toList();
  }

  /** Lets the tender at {@code position} refuse every refund: someone refunded it directly. */
  private void drain(Order o, int position) {
    Payment p = rows(o).get(position - 1);
    payments.refund(null, p.providerPaymentId(), p.capturedMinor(), "out-of-band");
  }

  private static List<Tuple> shares(Refund r) {
    return r.allocations().stream()
        .map(a -> tuple(a.seq(), a.position(), a.amountMinor(), a.state()))
        .toList();
  }

  @Test
  void reverseOrderRefundsTheLastTenderFirstAndRecordsEachShare() {
    Order o = giftThenCard();
    Refund r = service(AllocationPolicy.REVERSE_ORDER).create("acme", o.id(), 2000, List.of());
    assertThat(r.allocationPolicy()).isEqualTo(AllocationPolicy.REVERSE_ORDER);
    assertThat(shares(r))
        .containsExactly(
            tuple(1, 2, 1500L, RefundState.PENDING), tuple(2, 1, 500L, RefundState.PENDING));

    assertThat(worker.runOnce()).isEqualTo(1);

    Refund done = refund(r);
    assertThat(done.state()).isEqualTo(RefundState.SUCCEEDED);
    assertThat(done.returnedMinor()).isEqualTo(2000);
    assertThat(shares(done))
        .containsExactly(
            tuple(1, 2, 1500L, RefundState.SUCCEEDED), tuple(2, 1, 500L, RefundState.SUCCEEDED));
    assertThat(providerRefunds(o, 2)).containsExactly(r.id() + ":1500");
    assertThat(providerRefunds(o, 1)).containsExactly(r.id() + ":500");
    assertThat(done.providerRefundId())
        .as("the refund's id is its first share's")
        .isEqualTo(done.allocations().getFirst().providerRefundId());
    assertThat(rows(o))
        .extracting(Payment::refundedMinor, Payment::state)
        .containsExactly(
            tuple(500L, PaymentState.PARTIALLY_REFUNDED), tuple(1500L, PaymentState.REFUNDED));
    Order after = order(o);
    assertThat(after.state()).isEqualTo(OrderState.PARTIALLY_REFUNDED);
    assertThat(after.refundedMinor()).isEqualTo(2000);
    assertThat(payments.ledger())
        .filteredOn(e -> e.operation().equals("refund"))
        .extracting(PaymentProviderSimulator.LedgerEntry::reference)
        .as("made in the policy's order")
        .containsExactly(o.id() + "-2", o.id().toString());
  }

  @Test
  void eachPolicySplitsTheRefundItsOwnWayAndTheRestAlwaysBalances() {
    Order prop = giftThenCard();
    Refund half = service(AllocationPolicy.PROPORTIONAL).create("acme", prop.id(), 1250, List.of());
    assertThat(shares(half))
        .containsExactly(
            tuple(1, 2, 750L, RefundState.PENDING), tuple(2, 1, 500L, RefundState.PENDING));

    Order cardFirst = completed(tender("pm_card_ok", 1500), tender("pm_giftcard_ok", 1000));
    Refund most =
        service(AllocationPolicy.CARD_FIRST).create("acme", cardFirst.id(), 1600, List.of());
    assertThat(most.allocationPolicy()).isEqualTo(AllocationPolicy.CARD_FIRST);
    assertThat(shares(most))
        .as("the card, though it is tender 1, before the gift card")
        .containsExactly(
            tuple(1, 1, 1500L, RefundState.PENDING), tuple(2, 2, 100L, RefundState.PENDING));
    worker.runOnce();

    // What is left, split proportionally to what each tender still holds.
    Refund rest =
        service(AllocationPolicy.PROPORTIONAL).create("acme", cardFirst.id(), 900, List.of());
    assertThat(shares(rest)).containsExactly(tuple(1, 2, 900L, RefundState.PENDING));
    Refund prop2 =
        service(AllocationPolicy.PROPORTIONAL).create("acme", prop.id(), 1250, List.of());
    assertThat(shares(prop2))
        .as("the rest of each tender exactly")
        .containsExactly(
            tuple(1, 2, 750L, RefundState.PENDING), tuple(2, 1, 500L, RefundState.PENDING));
    worker.runOnce();

    for (Order o : List.of(prop, cardFirst)) {
      assertThat(order(o).state()).isEqualTo(OrderState.REFUNDED);
      assertThat(rows(o))
          .allSatisfy(
              p -> {
                assertThat(p.refundedMinor()).isEqualTo(p.capturedMinor());
                assertThat(p.state()).isEqualTo(PaymentState.REFUNDED);
              });
    }
    assertThat(providerRefunds(cardFirst, 1)).containsExactly(most.id() + ":1500");
    assertThat(providerRefunds(cardFirst, 2))
        .containsExactly(most.id() + ":100", rest.id() + ":900");
  }

  @Test
  void aTenderThatRefusesLeavesTheRefundPartiallyFailedAndItsShareFree() {
    Order o = giftThenCard();
    drain(o, 2);
    Service svc = service(AllocationPolicy.REVERSE_ORDER);
    Refund r = svc.create("acme", o.id(), 2000, List.of());
    worker.runOnce();

    Refund done = refund(r);
    assertThat(done.state()).isEqualTo(RefundState.PARTIALLY_FAILED);
    assertThat(done.returnedMinor()).isEqualTo(500);
    assertThat(done.activeMinor()).isEqualTo(500);
    assertThat(done.nextAttemptAt()).isNull();
    assertThat(done.failureCode()).isEqualTo("provider_refused");
    assertThat(done.failureMessage())
        .startsWith("tender 2 refused 1500: invalid_amount")
        .endsWith("; 500 of 2000 returned");
    assertThat(done.providerRefundId()).isEqualTo(done.allocations().get(1).providerRefundId());
    assertThat(shares(done))
        .as("the next share is still made after a refusal")
        .containsExactly(
            tuple(1, 2, 1500L, RefundState.FAILED), tuple(2, 1, 500L, RefundState.SUCCEEDED));
    assertThat(done.allocations().getFirst().failureCode()).isEqualTo("invalid_amount");
    assertThat(rows(o)).extracting(Payment::refundedMinor).containsExactly(500L, 0L);
    Order after = order(o);
    assertThat(after.state()).isEqualTo(OrderState.PARTIALLY_REFUNDED);
    assertThat(after.refundedMinor()).isEqualTo(500);
    assertThat(refunds.activeTotal(o.id())).isEqualTo(500);
    assertThat(
            jdbc.sql(
                    """
                    SELECT event_type || ':' || coalesce(payload->'refund'->>'state', '')
                        || ':' || coalesce(payload->'refund'->>'refunded', '')
                        || ':' || (payload->>'state')
                    FROM outbox WHERE aggregate_id = :id
                    ORDER BY aggregate_seq
                    """)
                .param("id", o.id())
                .query(String.class)
                .list())
        .as("the refund's failure says what came back, then the order moves by that much")
        .containsSubsequence(
            "refund.created:PENDING::COMPLETED",
            "refund.failed:PARTIALLY_FAILED:500:COMPLETED",
            "order.partially_refunded:::PARTIALLY_REFUNDED");

    // What didn't come back can be asked for again, and no more.
    assertThatThrownBy(() -> svc.create("acme", o.id(), 2001, List.of()))
        .isInstanceOf(RefundService.ExceedsCaptured.class)
        .hasMessage("refund of 2001 exceeds the 2000 left to refund");
    Refund again = service(AllocationPolicy.CARD_FIRST).create("acme", o.id(), 500, List.of());
    assertThat(shares(again))
        .as("the refused share freed the card's balance")
        .containsExactly(tuple(1, 2, 500L, RefundState.PENDING));
  }

  @Test
  void aRefundEveryTenderRefusesFailsWholeAndFreesItsLines() {
    Order o = giftThenCard();
    drain(o, 1);
    drain(o, 2);
    Service svc = service(AllocationPolicy.PROPORTIONAL);
    Refund r = svc.refundLines("acme", o.id(), List.of(new RefundLine("sku-1", 2)));
    assertThat(r.amountMinor()).isEqualTo(2000);
    worker.runOnce();

    Refund done = refund(r);
    assertThat(done.state()).isEqualTo(RefundState.FAILED);
    assertThat(done.returnedMinor()).isZero();
    assertThat(done.failureMessage())
        .contains("tender 2 refused 1200: invalid_amount")
        .contains("tender 1 refused 800: invalid_amount");
    assertThat(inventory.restocksOf(o.id().toString())).as("nothing restocked").isEmpty();
    assertThat(order(o).state()).isEqualTo(OrderState.COMPLETED);
    assertThat(refunds.activeLineQuantities(o.id())).isEmpty();
    assertThat(svc.refundLines("acme", o.id(), List.of(new RefundLine("sku-1", 2))).amountMinor())
        .as("the lines can be refunded again")
        .isEqualTo(2000);
  }

  @Test
  void aPartlyFailedLineRefundStillRestocksAndKeepsItsLines() {
    Order o = giftThenCard();
    drain(o, 2);
    Service svc = service(AllocationPolicy.REVERSE_ORDER);
    Refund r = svc.refundLines("acme", o.id(), List.of(new RefundLine("sku-1", 2)));
    worker.runOnce();
    Refund done = refund(r);
    assertThat(done.state()).isEqualTo(RefundState.PARTIALLY_FAILED);
    assertThat(done.returnedMinor()).isEqualTo(500);
    assertThat(shares(done))
        .containsExactly(
            tuple(1, 2, 1500L, RefundState.FAILED), tuple(2, 1, 500L, RefundState.SUCCEEDED));
    assertThat(inventory.restocksOf(o.id().toString()))
        .as("the goods came back, once, under the refund's id")
        .singleElement()
        .satisfies(x -> assertThat(x.id()).isEqualTo(r.id().toString()));
    assertThat(refunds.activeLineQuantities(o.id())).containsEntry("sku-1", 2L);
    assertThatThrownBy(() -> svc.refundLines("acme", o.id(), List.of(new RefundLine("sku-1", 1))))
        .as("the lines stay refunded")
        .isInstanceOf(RefundService.ExceedsPurchased.class);
    assertThat(svc.create("acme", o.id(), 1500, List.of()).amountMinor())
        .as("only the money that didn't come back is free")
        .isEqualTo(1500);
  }

  @Test
  void aCrashBetweenSharesResumesAtTheNextShareWithoutRefundingTwice() {
    Order o =
        completed(
            tender("pm_giftcard_ok", 500),
            tender("pm_giftcard_ok", 500),
            tender("pm_card_ok", 1500));
    Refund r = service(AllocationPolicy.REVERSE_ORDER).create("acme", o.id(), 2500, List.of());
    // The first share is recorded, then the worker dies.
    hooks.put(
        "share:REFUND",
        p -> {
          throw new Crash();
        });
    assertThatThrownBy(() -> worker.runOnce()).isInstanceOf(Crash.class);
    assertThat(shares(refund(r)))
        .containsExactly(
            tuple(1, 3, 1500L, RefundState.SUCCEEDED),
            tuple(2, 2, 500L, RefundState.PENDING),
            tuple(3, 1, 500L, RefundState.PENDING));

    // The next worker finds the lease expired, and the second share's answer is lost on its way
    // back: it dies after the provider refunded but before the share was recorded.
    clock.advance(props.lease().plusSeconds(1));
    hooks.put(
        "after:REFUND",
        p -> {
          throw new Crash();
        });
    assertThatThrownBy(() -> worker.runOnce()).isInstanceOf(Crash.class);
    assertThat(providerRefunds(o, 2)).containsExactly(r.id() + ":500");
    assertThat(refund(r).allocations().get(1).state()).isEqualTo(RefundState.PENDING);

    clock.advance(props.lease().plusSeconds(1));
    settings.honorIdempotencyKeys(false);
    assertThat(worker.runOnce()).isEqualTo(1);

    Refund done = refund(r);
    assertThat(done.state()).isEqualTo(RefundState.SUCCEEDED);
    assertThat(providerRefunds(o, 3)).containsExactly(r.id() + ":1500");
    assertThat(providerRefunds(o, 2))
        .as("found by its reference, not made again")
        .containsExactly(r.id() + ":500");
    assertThat(providerRefunds(o, 1)).containsExactly(r.id() + ":500");
    assertThat(order(o).state()).isEqualTo(OrderState.REFUNDED);
    assertThat(rows(o)).allMatch(p -> p.state() == PaymentState.REFUNDED);
  }

  @Test
  void theDatabaseKeepsSharesWithinEachTenderAndInStepWithTheirRefund() {
    Order o = giftThenCard();
    List<Payment> ts = rows(o);
    UUID gift = ts.get(0).id();
    UUID card = ts.get(1).id();
    // Over the gift card's 10.00, though within the order's 25.00.
    UUID r1 = rawRefund(o, 1100);
    assertThatThrownBy(() -> rawShare(r1, gift, 1, 1100))
        .hasMessageContaining("refund_allocations_within_tender");
    // Shares that don't add up to their refund.
    assertThatThrownBy(() -> rawShare(r1, card, 1, 600))
        .hasMessageContaining("refund_allocations_add_up");
    // Another order's tender.
    Order other = giftThenCard();
    assertThatThrownBy(() -> rawShare(r1, rows(other).get(1).id(), 1, 1100))
        .hasMessageContaining("refund_allocations_own_tenders");
    // A refund settled while its share is pending, by this binary.
    rawShare(r1, card, 1, 1100);
    assertThatThrownBy(
            () ->
                jdbc.sql(
                        """
                        UPDATE refunds SET state = 'SUCCEEDED', provider_refund_id = 're_x',
                            next_attempt_at = NULL, returned_minor = 1100
                        WHERE id = :id
                          AND set_config('payflow.refund_writer', 'allocations', true) = 'allocations'
                        """)
                    .param("id", r1)
                    .update())
        .hasMessageContaining("refund_allocations_settled");
    // A partial failure must say what came back, and agree with its shares.
    assertThatThrownBy(
            () ->
                jdbc.sql(
                        """
                        UPDATE refunds SET state = 'PARTIALLY_FAILED', provider_refund_id = 're_x',
                            next_attempt_at = NULL, failure_code = 'provider_refused'
                        WHERE id = :id
                        """)
                    .param("id", r1)
                    .update())
        .hasMessageContaining("refunds_returned");
    // A settled share is final, and a share's amount never changes.
    jdbc.sql(
            """
            UPDATE refund_allocations SET state = 'FAILED', failure_code = 'x'
            WHERE refund_id = :id
            """)
        .param("id", r1)
        .update();
    assertThatThrownBy(
            () ->
                jdbc.sql(
                        """
                        UPDATE refund_allocations SET state = 'SUCCEEDED',
                            provider_refund_id = 're_y'
                        WHERE refund_id = :id
                        """)
                    .param("id", r1)
                    .update())
        .hasMessageContaining("refund_allocations_legal_transition");
    assertThatThrownBy(
            () ->
                jdbc.sql("UPDATE refund_allocations SET amount_minor = 1 WHERE refund_id = :id")
                    .param("id", r1)
                    .update())
        .hasMessageContaining("refund_allocations_immutable");
  }

  @Test
  void anOlderBinarysRefundIsSplitWhenClaimedAndItsSettlementIsMirroredToItsShare() {
    Order o = completed(tender("pm_card_ok", 2500));
    // Created the way the previous release does: a refund row, no shares, no policy.
    UUID old = rawRefund(o, 700);
    assertThat(refunds.findById(old).orElseThrow().allocations()).isEmpty();
    worker.runOnce();
    Refund done = refunds.findById(old).orElseThrow();
    assertThat(done.state()).isEqualTo(RefundState.SUCCEEDED);
    assertThat(done.allocationPolicy()).isEqualTo(AllocationPolicy.REVERSE_ORDER);
    assertThat(shares(done)).containsExactly(tuple(1, 1, 700L, RefundState.SUCCEEDED));
    assertThat(providerRefunds(o, 1)).containsExactly(old + ":700");
    assertThat(order(o).refundedMinor()).isEqualTo(700);

    // This binary split it; the previous release's worker settles it by its state alone, and
    // records the money on the order and its tender itself.
    Refund split = service(AllocationPolicy.REVERSE_ORDER).create("acme", o.id(), 300, List.of());
    jdbc.sql(
            """
            UPDATE refunds SET state = 'SUCCEEDED', provider_refund_id = 're_old',
                next_attempt_at = NULL, updated_at = now()
            WHERE id = :id
            """)
        .param("id", split.id())
        .update();
    Refund mirrored = refund(split);
    assertThat(mirrored.returnedMinor()).isEqualTo(300);
    assertThat(mirrored.allocations())
        .singleElement()
        .satisfies(
            a -> {
              assertThat(a.state()).isEqualTo(RefundState.SUCCEEDED);
              assertThat(a.providerRefundId()).isEqualTo("re_old");
            });
  }

  /** A pending refund of {@code amount} with no shares, as the previous release creates it. */
  private UUID rawRefund(Order o, long amount) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO refunds (id, order_id, amount_minor, state, attempt, next_attempt_at,
                created_at, updated_at)
            VALUES (:id, :order, :amount, 'PENDING', 0, now(), now(), now())
            """)
        .param("id", id)
        .param("order", o.id())
        .param("amount", amount)
        .update();
    return id;
  }

  private void rawShare(UUID refund, UUID payment, int seq, long amount) {
    jdbc.sql(
            """
            INSERT INTO refund_allocations (refund_id, payment_id, seq, amount_minor, state,
                created_at, updated_at)
            VALUES (:refund, :payment, :seq, :amount, 'PENDING', now(), now())
            """)
        .param("refund", refund)
        .param("payment", payment)
        .param("seq", seq)
        .param("amount", amount)
        .update();
  }
}
