package io.github.laiwf94.payflow.saga.steps;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.laiwf94.payflow.PayflowApplication;
import io.github.laiwf94.payflow.money.Money;
import io.github.laiwf94.payflow.order.LineItem;
import io.github.laiwf94.payflow.order.Order;
import io.github.laiwf94.payflow.order.OrderRepository;
import io.github.laiwf94.payflow.order.OrderService;
import io.github.laiwf94.payflow.order.OrderState;
import io.github.laiwf94.payflow.order.OrderTransitions;
import io.github.laiwf94.payflow.order.ShippingAddress;
import io.github.laiwf94.payflow.refund.Refund;
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
import io.github.laiwf94.payflow.sim.FaultPlan;
import io.github.laiwf94.payflow.sim.Faults;
import io.github.laiwf94.payflow.sim.InventorySimulator;
import io.github.laiwf94.payflow.sim.PaymentProviderSimulator;
import io.github.laiwf94.payflow.sim.PaymentProviderSimulator.Payment;
import io.github.laiwf94.payflow.sim.ShippingSimulator;
import io.github.laiwf94.payflow.sim.SimulatorSettings;
import io.github.laiwf94.payflow.support.Db;
import io.github.laiwf94.payflow.support.MutableClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AfterContainer;
import net.jqwik.api.statistics.Statistics;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Random failure schedules across all three dependencies and random crash points in the worker,
 * against real Postgres. After every order settles, the providers' own ledgers must agree with the
 * invariants a payments team would check in a reconciliation:
 *
 * <ul>
 *   <li>every order is in a terminal state;
 *   <li>a completed order was charged exactly its amount, once;
 *   <li>a failed or cancelled order kept nothing: no open hold, nothing captured net of refunds;
 *   <li>no order was ever authorized or captured twice;
 *   <li>every refund settled, and the order's refunded total is exactly its succeeded refunds, each
 *       of which the provider holds once under the refund's own id and once in its ledger;
 *   <li>a refund of lines put exactly those units back on the shelf, once, under its own id, and
 *       the line refunds of an order add up to what the order paid for the units they returned;
 *   <li>no refund request was refused unless what had been refunded by the end proves it had to be;
 *   <li>for every product, stock left plus stock held equals the stock it started with, and what is
 *       held is exactly what completed orders bought less what their refunds returned;
 *   <li>each order's events describe a legal path through the state machine.
 * </ul>
 *
 * <p>Once every order has settled, refunds arrive in up to four bursts, one per round, so a burst
 * lands while the refunds of the last are still pending, failing or being retried across crashes.
 * Each burst's requests start together on their own threads and race for the same orders' captures
 * and lines. Their winners vary from run to run, so the checks are ones that hold for every
 * interleaving: the money and stock invariants, and that each refusal is justified by the refunds
 * that succeeded.
 */
class SagaChaosProperties {

  private static ConfigurableApplicationContext ctx;

  /** One Spring context for all tries; each try gets fresh simulators and empty tables. */
  private static synchronized ConfigurableApplicationContext context() {
    if (ctx == null) {
      ctx =
          new SpringApplicationBuilder(PayflowApplication.class).web(WebApplicationType.NONE).run();
    }
    return ctx;
  }

  @AfterContainer
  static void close() {
    if (ctx != null) {
      ctx.close();
      ctx = null;
    }
  }

  record Rates(double error, double lostBefore, double lostAfter) {}

  record OrderSpec(String method, boolean undeliverable, List<LineItem> lines, int cancelAt) {}

  enum RefundKind {
    /** Part of the money, no goods. */
    AMOUNT,
    /** Some units of one or two of the order's lines, priced by the order. */
    LINES,
    /** Whatever is left: the full refund. */
    REMAINING
  }

  /** {@code quantity} units of the sku on the order's line {@code line} (modulo its lines). */
  record LinePick(int line, int quantity) {}

  /**
   * One refund request in burst {@code burst} against order {@code order}, modulo the orders that
   * completed (or modulo all of them when none did, so refusals of the rest are covered too). An
   * amount refund asks for {@code permille} thousandths of the order's amount, at least 1.
   */
  record RefundSpec(int burst, int order, RefundKind kind, int permille, List<LinePick> picks) {}

  /** What a request asked for and how the service answered it. */
  record Asked(UUID orderId, RefundKind kind, long amount, List<RefundLine> lines, String result) {}

  record Crash(String point, int occurrence) {}

  record Scenario(
      long seed,
      boolean honorKeys,
      Rates payment,
      Rates inventory,
      Rates shipping,
      List<OrderSpec> orders,
      List<Crash> crashes,
      List<RefundSpec> refunds) {}

  @Provide
  Arbitrary<Scenario> scenarios() {
    Arbitrary<Rates> rates =
        Combinators.combine(
                // Up to 60% failures, so forward steps regularly run out of attempts and the
                // compensations have to clean up after calls that half happened.
                Arbitraries.doubles().between(0, 0.3),
                Arbitraries.doubles().between(0, 0.15),
                Arbitraries.doubles().between(0, 0.15))
            .as(Rates::new);
    // A sku may come up on two lines of one order at two prices, which is what line pricing has to
    // get right.
    Arbitrary<LineItem> line =
        Combinators.combine(
                Arbitraries.of("sku-a", "sku-b", "sku-scarce"),
                Arbitraries.integers().between(1, 3),
                Arbitraries.of(250L, 1000L, 1999L))
            .as(LineItem::new);
    Arbitrary<OrderSpec> order =
        Combinators.combine(
                Arbitraries.of(
                    "pm_card_ok",
                    "pm_card_ok",
                    "pm_card_ok",
                    "pm_card_declined",
                    "pm_card_expired"),
                Arbitraries.of(false, false, false, true),
                line.list().ofMinSize(1).ofMaxSize(3),
                Arbitraries.integers().between(-1, 30))
            .as(OrderSpec::new);
    Arbitrary<RefundSpec> refund =
        Combinators.combine(
                Arbitraries.integers().between(0, 3),
                Arbitraries.integers().between(0, 7),
                Arbitraries.of(
                    RefundKind.AMOUNT, RefundKind.LINES, RefundKind.LINES, RefundKind.REMAINING),
                Arbitraries.integers().between(1, 1000),
                Combinators.combine(
                        Arbitraries.integers().between(0, 2), Arbitraries.integers().between(1, 3))
                    .as(LinePick::new)
                    .list()
                    .ofMinSize(1)
                    .ofMaxSize(2))
            .as(RefundSpec::new);
    Arbitrary<Crash> crash =
        Combinators.combine(
                Arbitraries.of("before", "after", "committed")
                    .flatMap(
                        when ->
                            Arbitraries.of(
                                    "PENDING",
                                    "INVENTORY_RESERVED",
                                    "PAYMENT_AUTHORIZED",
                                    "PAYMENT_CAPTURED",
                                    "CANCELLING_SHIPMENT",
                                    "REVERSING_PAYMENT",
                                    "RELEASING_INVENTORY",
                                    "REFUND")
                                .map(s -> when + ":" + s)),
                Arbitraries.integers().between(1, 4))
            .as(Crash::new);
    return Combinators.combine(
            Arbitraries.longs(),
            Arbitraries.of(true, false),
            rates,
            rates,
            rates,
            order.list().ofMinSize(1).ofMaxSize(8),
            crash.list().ofMaxSize(6),
            refund.list().ofMaxSize(12))
        .as(Scenario::new);
  }

  /** Stands in for a killed process: an Error, so no step or worker code catches it. */
  static final class SimulatedCrash extends Error {
    private static final long serialVersionUID = 1L;
  }

  @Property(tries = 200)
  void ordersSettleWithoutLosingOrDuplicatingMoneyOrStock(@ForAll("scenarios") Scenario sc)
      throws InterruptedException {
    ConfigurableApplicationContext c = context();
    JdbcClient jdbc = c.getBean(JdbcClient.class);
    OrderRepository repo = c.getBean(OrderRepository.class);
    OrderTransitions transitions = c.getBean(OrderTransitions.class);
    RefundRepository refunds = c.getBean(RefundRepository.class);
    RefundTransitions refundTransitions = c.getBean(RefundTransitions.class);
    RefundService refundService = c.getBean(RefundService.class);
    Db.truncateAll(jdbc);

    SplittableRandom random = new SplittableRandom(sc.seed());
    Faults faults = new Faults(random.split());
    SimulatorSettings settings = new SimulatorSettings(Duration.ZERO);
    settings.honorIdempotencyKeys(sc.honorKeys());
    PaymentProviderSimulator payments = new PaymentProviderSimulator(faults, settings);
    InventorySimulator inventory = new InventorySimulator(faults, 1000);
    inventory.setStock("sku-scarce", 2);
    ShippingSimulator shipping = new ShippingSimulator(faults);
    plan(faults, "payment", sc.payment(), "authorize", "capture", "void", "refund", "lookup");
    plan(faults, "inventory", sc.inventory(), "reserve", "release", "restock", "lookup");
    plan(faults, "shipping", sc.shipping(), "create", "cancel", "lookup");

    SagaProperties props =
        new SagaProperties(
            false,
            Duration.ofSeconds(30),
            Duration.ofMillis(10),
            64,
            64,
            Duration.ofMillis(100),
            Duration.ofSeconds(5),
            6,
            Duration.ofSeconds(60),
            Duration.ofSeconds(1),
            Duration.ofSeconds(5));
    UnknownOutcomes unknown = new UnknownOutcomes(new SimpleMeterRegistry());
    var pay = DirectGateways.payments(payments);
    var inv = DirectGateways.inventory(inventory);
    var ship = DirectGateways.shipping(shipping);
    List<SagaStep> steps =
        List.of(
            new ReserveInventoryStep(inv),
            new AuthorizePaymentStep(pay, props, unknown),
            new CapturePaymentStep(pay, props, unknown),
            new CreateShipmentStep(ship),
            new CancelShipmentStep(ship),
            new ReversePaymentStep(pay, props, unknown),
            new ReleaseInventoryStep(inv));
    RefundStep refundStep = new RefundStep(pay, inv, props, unknown);

    MutableClock clock = new MutableClock(Instant.parse("2030-01-01T00:00:00Z"));
    Map<String, Integer> seen = new HashMap<>();
    List<Crash> pendingCrashes = new ArrayList<>(sc.crashes());
    CrashPoints crash =
        point -> {
          int n = seen.merge(point, 1, Integer::sum);
          if (pendingCrashes.removeIf(cr -> cr.point().equals(point) && cr.occurrence() == n)) {
            throw new SimulatedCrash();
          }
        };

    List<Order> orders = new ArrayList<>();
    for (OrderSpec spec : sc.orders()) {
      long amount = spec.lines().stream().mapToLong(l -> l.quantity() * l.unitAmount()).sum();
      Order o =
          Order.create(
              UUID.randomUUID(),
              "acme",
              Money.of(amount, "EUR"),
              spec.lines(),
              new ShippingAddress(
                  "Alice Example",
                  "1 Main Street",
                  null,
                  "Springfield",
                  spec.undeliverable() ? ShippingSimulator.UNDELIVERABLE_POSTAL_CODE : "12345",
                  "NL"),
              spec.method(),
              "chaos",
              clock.instant());
      transitions.create(o);
      orders.add(o);
    }

    SagaWorker worker = worker(repo, transitions, steps, props, clock, crash, random);
    RefundWorker refundWorker =
        refundWorker(refunds, repo, refundTransitions, refundStep, props, clock, crash, random);
    int lastBurst = sc.refunds().stream().mapToInt(RefundSpec::burst).max().orElse(-1);
    // The refund round: -1 until every order has settled, then one burst of refunds per round.
    int refundRound = -1;
    List<Asked> asked = new ArrayList<>();
    List<Order> targets = orders;
    for (int round = 0; round < 2000; round++) {
      for (int i = 0; i < orders.size(); i++) {
        if (sc.orders().get(i).cancelAt() == round) {
          transitions.requestCancel("acme", orders.get(i).id(), clock.instant());
        }
      }
      if (refundRound >= 0) {
        int burst = refundRound;
        asked.addAll(
            race(
                refundService,
                targets,
                sc.refunds().stream().filter(r -> r.burst() == burst).toList()));
      }
      try {
        worker.runOnce();
        refundWorker.runOnce();
      } catch (SimulatedCrash e) {
        // The process died. A new one starts; the dead one's leases run out first.
        clock.advance(props.lease().plusSeconds(1));
        worker = worker(repo, transitions, steps, props, clock, crash, random);
        refundWorker =
            refundWorker(refunds, repo, refundTransitions, refundStep, props, clock, crash, random);
      }
      List<Order> now = orders.stream().map(o -> repo.findById(o.id()).orElseThrow()).toList();
      boolean refundsSettled =
          now.stream()
              .flatMap(o -> refunds.listForOrder(o.id()).stream())
              .allMatch(r -> r.state().isTerminal());
      boolean settled = refundsSettled && now.stream().allMatch(o -> o.state().isTerminal());
      if (refundRound >= 0) {
        refundRound++;
      } else if (settled) {
        refundRound = 0;
        List<UUID> completed =
            now.stream().filter(o -> o.state() == OrderState.COMPLETED).map(Order::id).toList();
        if (!completed.isEmpty()) {
          targets = orders.stream().filter(o -> completed.contains(o.id())).toList();
        }
      }
      if (settled && refundRound > lastBurst) {
        break;
      }
      clock.advance(Duration.ofMillis(random.nextLong(5_000)));
    }
    assertThat(refundRound).as("every refund burst was sent").isGreaterThan(lastBurst);

    checkInvariants(jdbc, repo, refunds, orders, payments, inventory, shipping);
    checkRefusals(repo, refunds, asked);
    for (Order o : orders) {
      Statistics.label("final state").collect(repo.findById(o.id()).orElseThrow().state());
    }
    for (Asked a : asked) {
      Statistics.label("refund requests").collect(a.kind() + " " + a.result());
    }
    Statistics.label("crashes").collect(sc.crashes().size() - pendingCrashes.size());
  }

  /**
   * Sends one burst of refund requests at once, each on its own thread, released together so they
   * contend for the same orders' row locks.
   */
  private static List<Asked> race(RefundService service, List<Order> orders, List<RefundSpec> burst)
      throws InterruptedException {
    if (burst.isEmpty()) {
      return List.of();
    }
    CountDownLatch go = new CountDownLatch(1);
    List<Future<Asked>> answers = new ArrayList<>();
    try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
      for (RefundSpec spec : burst) {
        Order order = orders.get(spec.order() % orders.size());
        answers.add(
            threads.submit(
                () -> {
                  go.await();
                  return ask(service, order, spec);
                }));
      }
      go.countDown();
      List<Asked> out = new ArrayList<>();
      for (Future<Asked> f : answers) {
        try {
          out.add(f.get());
        } catch (ExecutionException e) {
          throw new AssertionError("refund request failed unexpectedly", e.getCause());
        }
      }
      return out;
    }
  }

  private static Asked ask(RefundService service, Order order, RefundSpec spec) {
    long amount = 0;
    List<RefundLine> lines = List.of();
    try {
      switch (spec.kind()) {
        case AMOUNT -> {
          amount = Math.max(1, order.amount().minorUnits() * spec.permille() / 1000);
          service.create("acme", order.id(), amount, List.of());
        }
        case LINES -> {
          Map<String, Integer> bySku = new LinkedHashMap<>();
          for (LinePick p : spec.picks()) {
            bySku.putIfAbsent(
                order.items().get(p.line() % order.items().size()).sku(), p.quantity());
          }
          lines =
              bySku.entrySet().stream().map(e -> new RefundLine(e.getKey(), e.getValue())).toList();
          amount = service.refundLines("acme", order.id(), lines).amountMinor();
        }
        case REMAINING -> service.refundRemaining("acme", order.id());
      }
      return new Asked(order.id(), spec.kind(), amount, lines, "accepted");
    } catch (RefundService.ExceedsCaptured e) {
      return new Asked(order.id(), spec.kind(), amount, lines, "exceeds_captured");
    } catch (RefundService.ExceedsPurchased e) {
      return new Asked(order.id(), spec.kind(), amount, lines, "exceeds_purchased");
    } catch (OrderService.WrongState e) {
      assertThat(e.code()).isEqualTo("order_not_refundable");
      return new Asked(order.id(), spec.kind(), amount, lines, "not_refundable");
    }
  }

  /**
   * Every refusal must be one the final state proves. No refund fails in this property and none is
   * ever removed, so what an order has refunded by the end is at least what was promised back when
   * a request was refused: a request that would fit even then was refused wrongly.
   */
  private static void checkRefusals(
      OrderRepository repo, RefundRepository refunds, List<Asked> asked) {
    for (Asked a : asked) {
      Order o = repo.findById(a.orderId()).orElseThrow();
      Map<String, Long> given = refundedUnits(refunds.listForOrder(o.id()));
      switch (a.result()) {
        case "accepted" -> {}
        case "not_refundable" ->
            assertThat(o.state())
                .as("%s refused as not refundable", a)
                .isIn(OrderState.FAILED, OrderState.CANCELLED, OrderState.REFUNDED);
        case "exceeds_purchased" ->
            assertThat(a.lines())
                .as("%s refused for goods, with %s already given back", a, given)
                .anyMatch(l -> given.getOrDefault(l.sku(), 0L) + l.quantity() > bought(o, l.sku()));
        case "exceeds_captured" -> {
          // A line refund takes the next units of each sku, after those earlier refunds took, and
          // those can only have been fewer than the final count. Its share was at least the
          // cheapest such run of units that would still fit in what the order bought.
          long least =
              a.kind() == RefundKind.LINES
                  ? a.lines().stream()
                      .mapToLong(
                          l -> {
                            long last =
                                Math.min(
                                    given.getOrDefault(l.sku(), 0L),
                                    bought(o, l.sku()) - l.quantity());
                            assertThat(last)
                                .as("%s fits in what was bought, or it'd be exceeds_purchased", l)
                                .isNotNegative();
                            long min = Long.MAX_VALUE;
                            for (long from = 0; from <= last; from++) {
                              min = Math.min(min, unitsCost(o, l.sku(), from, l.quantity()));
                            }
                            return min;
                          })
                      .sum()
                  : a.amount();
          assertThat(o.refundedMinor() + least)
              .as("%s refused for money, with %d refunded", a, o.refundedMinor())
              .isGreaterThan(o.capturedMinor());
        }
        default -> throw new AssertionError("unexpected answer " + a);
      }
    }
  }

  private static long bought(Order o, String sku) {
    return o.items().stream().filter(i -> i.sku().equals(sku)).mapToLong(LineItem::quantity).sum();
  }

  /** Units per sku that the succeeded refunds among {@code rows} gave back. */
  private static Map<String, Long> refundedUnits(List<Refund> rows) {
    Map<String, Long> units = new HashMap<>();
    rows.stream()
        .filter(r -> r.state() == RefundState.SUCCEEDED)
        .flatMap(r -> r.lines().stream())
        .forEach(l -> units.merge(l.sku(), (long) l.quantity(), Long::sum));
    return units;
  }

  /** What the order paid for the first {@code units} of {@code sku}, its lines in order. */
  private static long firstUnitsCost(Order o, String sku, long units) {
    assertThat(units)
        .as("refunded more %s than %s bought", sku, o.id())
        .isLessThanOrEqualTo(bought(o, sku));
    return unitsCost(o, sku, 0, units);
  }

  /**
   * What the order paid for {@code count} units of {@code sku} after its first {@code from}, its
   * lines in order, as the service prices a line refund.
   */
  private static long unitsCost(Order o, String sku, long from, long count) {
    long skip = from;
    long left = count;
    long cost = 0;
    for (LineItem i : o.items()) {
      if (i.sku().equals(sku)) {
        long skipped = Math.min(skip, i.quantity());
        skip -= skipped;
        long take = Math.min(left, i.quantity() - skipped);
        cost += take * i.unitAmount();
        left -= take;
      }
    }
    return cost;
  }

  private static SagaWorker worker(
      OrderRepository repo,
      OrderTransitions transitions,
      List<SagaStep> steps,
      SagaProperties props,
      MutableClock clock,
      CrashPoints crash,
      SplittableRandom random) {
    return new SagaWorker(
        repo,
        transitions,
        steps,
        props,
        clock,
        new Backoff(props.backoffBase(), props.backoffCap(), random.split()),
        crash,
        new SimpleMeterRegistry());
  }

  private static RefundWorker refundWorker(
      RefundRepository refunds,
      OrderRepository repo,
      RefundTransitions transitions,
      RefundStep step,
      SagaProperties props,
      MutableClock clock,
      CrashPoints crash,
      SplittableRandom random) {
    return new RefundWorker(
        refunds,
        repo,
        transitions,
        step,
        props,
        clock,
        new Backoff(props.backoffBase(), props.backoffCap(), random.split()),
        crash,
        new SimpleMeterRegistry());
  }

  private static void plan(Faults faults, String dependency, Rates r, String... ops) {
    for (String op : ops) {
      faults.set(
          dependency + "." + op,
          new FaultPlan(r.error(), r.lostBefore(), r.lostAfter(), Duration.ZERO, List.of()));
    }
  }

  private static void checkInvariants(
      JdbcClient jdbc,
      OrderRepository repo,
      RefundRepository refunds,
      List<Order> orders,
      PaymentProviderSimulator payments,
      InventorySimulator inventory,
      ShippingSimulator shipping) {
    for (Order created : orders) {
      Order o = repo.findById(created.id()).orElseThrow();
      String ref = o.id().toString();
      List<Payment> ps = payments.paymentsFor(ref);
      long authorizations =
          payments.ledgerFor(ref).stream().filter(e -> e.operation().equals("authorize")).count();
      long captures =
          payments.ledgerFor(ref).stream().filter(e -> e.operation().equals("capture")).count();
      long captured = ps.stream().mapToLong(Payment::captured).sum();
      long refunded = ps.stream().mapToLong(Payment::refunded).sum();
      long amount = o.amount().minorUnits();
      var shipment = shipping.shipment(ref);

      assertThat(o.state().isTerminal()).as("%s ended in %s", ref, o.state()).isTrue();
      assertThat(authorizations).as("authorizations for %s", ref).isLessThanOrEqualTo(1);
      assertThat(captures).as("captures for %s", ref).isLessThanOrEqualTo(1);
      assertThat(o.capturedMinor()).as("recorded capture matches provider").isEqualTo(captured);
      assertThat(o.refundedMinor()).as("recorded refund matches provider").isEqualTo(refunded);
      List<Refund> rows = refunds.listForOrder(o.id());
      assertThat(rows).as("refunds of %s settled", ref).allMatch(r -> r.state().isTerminal());
      List<Refund> succeeded =
          rows.stream().filter(r -> r.state() == RefundState.SUCCEEDED).toList();
      if (!rows.isEmpty()) {
        // Only completed orders take refund rows, so their payment saw no reversal.
        List<PaymentProviderSimulator.Refund> atProvider = payments.refundsFor(o.paymentId());
        assertThat(atProvider)
            .extracting(PaymentProviderSimulator.Refund::reference)
            .as("provider refunds of %s are exactly its succeeded rows, once each", ref)
            .containsExactlyInAnyOrderElementsOf(
                succeeded.stream().map(r -> r.id().toString()).toList());
        assertThat(succeeded)
            .allMatch(
                r ->
                    atProvider.stream()
                        .anyMatch(
                            p ->
                                p.id().equals(r.providerRefundId())
                                    && p.amount() == r.amountMinor()));
        assertThat(succeeded.stream().mapToLong(Refund::amountMinor).sum())
            .as("succeeded refunds of %s add up to its refunded total", ref)
            .isEqualTo(o.refundedMinor());
        // The provider never refuses a refund of money it holds, so none may fail here.
        assertThat(rows).as("refunds of %s", ref).allMatch(r -> r.state() != RefundState.FAILED);
        List<PaymentProviderSimulator.LedgerEntry> ledgerRefunds =
            payments.ledgerFor(ref).stream().filter(e -> e.operation().equals("refund")).toList();
        assertThat(ledgerRefunds)
            .extracting(PaymentProviderSimulator.LedgerEntry::amount)
            .as("provider ledger refunds of %s are exactly its succeeded rows", ref)
            .containsExactlyInAnyOrderElementsOf(
                succeeded.stream().map(Refund::amountMinor).toList());

        String reservation = o.reservationId() != null ? o.reservationId() : ref;
        List<Refund> lineRefunds = succeeded.stream().filter(r -> !r.lines().isEmpty()).toList();
        assertThat(inventory.restocksOf(reservation))
            .extracting(InventorySimulator.Restock::id)
            .as("restocks of %s are exactly its succeeded line refunds, once each", ref)
            .containsExactlyInAnyOrderElementsOf(
                lineRefunds.stream().map(r -> r.id().toString()).toList());
        for (InventorySimulator.Restock x : inventory.restocksOf(reservation)) {
          Refund r =
              lineRefunds.stream()
                  .filter(l -> l.id().toString().equals(x.id()))
                  .findFirst()
                  .orElseThrow();
          assertThat(x.lines())
              .as("restock %s returned its refund's lines", x.id())
              .containsExactlyElementsOf(
                  r.lines().stream()
                      .map(l -> new InventorySimulator.Line(l.sku(), l.quantity()))
                      .toList());
        }
        // Each line refund took the next units of its skus, so together they returned the first
        // units of each sku, whatever order they won the lock in.
        long lineCost =
            refundedUnits(rows).entrySet().stream()
                .mapToLong(e -> firstUnitsCost(o, e.getKey(), e.getValue()))
                .sum();
        assertThat(lineRefunds.stream().mapToLong(Refund::amountMinor).sum())
            .as("line refunds of %s add up to what their units cost", ref)
            .isEqualTo(lineCost);
      }
      switch (o.state()) {
        case COMPLETED -> {
          assertThat(captured - refunded)
              .as("completed %s charged its amount", ref)
              .isEqualTo(amount);
          assertThat(shipment)
              .get()
              .extracting(ShippingSimulator.Shipment::status)
              .isEqualTo(ShippingSimulator.Status.LABEL_CREATED);
          assertThat(inventory.reservation(ref)).as("shipped goods stay held").isPresent();
        }
        case PARTIALLY_REFUNDED -> {
          assertThat(captured).isEqualTo(amount);
          assertThat(refunded).as("part of %s refunded", ref).isBetween(1L, amount - 1);
          assertThat(shipment)
              .get()
              .extracting(ShippingSimulator.Shipment::status)
              .isEqualTo(ShippingSimulator.Status.LABEL_CREATED);
          assertThat(inventory.reservation(ref)).isPresent();
        }
        case REFUNDED -> {
          assertThat(captured).isEqualTo(amount);
          assertThat(refunded).isEqualTo(amount);
          assertThat(inventory.reservation(ref)).isPresent();
        }
        case FAILED, CANCELLED -> {
          assertThat(captured - refunded).as("%s %s kept money", o.state(), ref).isZero();
          assertThat(ps)
              .as("no hold left open for %s", ref)
              .noneMatch(p -> p.status() == PaymentProviderSimulator.Status.AUTHORIZED);
          assertThat(inventory.reservation(ref)).as("stock released for %s", ref).isEmpty();
          shipment.ifPresent(
              s ->
                  assertThat(s.status())
                      .as("shipment cancelled for %s", ref)
                      .isEqualTo(ShippingSimulator.Status.CANCELLED));
        }
        default -> throw new AssertionError("not terminal: " + o.state());
      }
      List<String> states =
          jdbc.sql(
                  """
                  SELECT payload->>'state' FROM outbox
                  WHERE aggregate_id = :id ORDER BY aggregate_seq
                  """)
              .param("id", o.id())
              .query(String.class)
              .list();
      OrderState prev = OrderState.valueOf(states.get(0));
      assertThat(prev).isEqualTo(OrderState.PENDING);
      for (String s : states.subList(1, states.size())) {
        OrderState next = OrderState.valueOf(s);
        assertThat(next == prev || prev.canTransitionTo(next))
            .as("event path %s for %s", states, ref)
            .isTrue();
        prev = next;
      }
      assertThat(prev).isEqualTo(o.state());
    }
    for (String sku : List.of("sku-a", "sku-b", "sku-scarce")) {
      assertThat(inventory.stock(sku) + inventory.heldFor(sku))
          .as("stock of %s balances", sku)
          .isEqualTo(inventory.initialStock(sku));
      long kept = 0;
      for (Order created : orders) {
        Order o = repo.findById(created.id()).orElseThrow();
        if (o.state() != OrderState.FAILED && o.state() != OrderState.CANCELLED) {
          kept +=
              bought(o, sku) - refundedUnits(refunds.listForOrder(o.id())).getOrDefault(sku, 0L);
        }
      }
      assertThat(inventory.heldFor(sku))
          .as("held %s is what shipped orders bought less what refunds returned", sku)
          .isEqualTo(kept);
    }
  }
}
