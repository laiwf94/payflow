package io.github.laiwf94.payflow.saga.steps;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.laiwf94.payflow.PayflowApplication;
import io.github.laiwf94.payflow.money.Money;
import io.github.laiwf94.payflow.order.LineItem;
import io.github.laiwf94.payflow.order.Order;
import io.github.laiwf94.payflow.order.OrderRepository;
import io.github.laiwf94.payflow.order.OrderState;
import io.github.laiwf94.payflow.order.OrderTransitions;
import io.github.laiwf94.payflow.order.ShippingAddress;
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
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;
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
 *   <li>for every product, stock left plus stock held equals the stock it started with, and only
 *       completed orders hold any;
 *   <li>each order's events describe a legal path through the state machine.
 * </ul>
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

  record OrderSpec(String method, boolean undeliverable, String sku, int quantity, int cancelAt) {}

  record Crash(String point, int occurrence) {}

  record Scenario(
      long seed,
      boolean honorKeys,
      Rates payment,
      Rates inventory,
      Rates shipping,
      List<OrderSpec> orders,
      List<Crash> crashes,
      boolean refundCompleted) {}

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
    Arbitrary<OrderSpec> order =
        Combinators.combine(
                Arbitraries.of(
                    "pm_card_ok",
                    "pm_card_ok",
                    "pm_card_ok",
                    "pm_card_declined",
                    "pm_card_expired"),
                Arbitraries.of(false, false, false, true),
                Arbitraries.of("sku-a", "sku-b", "sku-scarce"),
                Arbitraries.integers().between(1, 3),
                Arbitraries.integers().between(-1, 30))
            .as(OrderSpec::new);
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
                                    "REFUNDING")
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
            Arbitraries.of(true, false))
        .as(Scenario::new);
  }

  /** Stands in for a killed process: an Error, so no step or worker code catches it. */
  static final class SimulatedCrash extends Error {
    private static final long serialVersionUID = 1L;
  }

  @Property(tries = 200)
  void ordersSettleWithoutLosingOrDuplicatingMoneyOrStock(@ForAll("scenarios") Scenario sc) {
    ConfigurableApplicationContext c = context();
    JdbcClient jdbc = c.getBean(JdbcClient.class);
    OrderRepository repo = c.getBean(OrderRepository.class);
    OrderTransitions transitions = c.getBean(OrderTransitions.class);
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
    plan(faults, "inventory", sc.inventory(), "reserve", "release", "lookup");
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
            Duration.ofSeconds(1));
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
            new ReleaseInventoryStep(inv),
            new RefundStep(pay, props, unknown));

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
      Order o =
          Order.create(
              UUID.randomUUID(),
              "acme",
              Money.of(1000L * spec.quantity(), "EUR"),
              List.of(new LineItem(spec.sku(), spec.quantity(), 1000)),
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
    boolean refundsRequested = false;
    for (int round = 0; round < 2000; round++) {
      for (int i = 0; i < orders.size(); i++) {
        if (sc.orders().get(i).cancelAt() == round) {
          transitions.requestCancel("acme", orders.get(i).id(), clock.instant());
        }
      }
      try {
        worker.runOnce();
      } catch (SimulatedCrash e) {
        // The process died. A new one starts; the dead one's leases run out first.
        clock.advance(props.lease().plusSeconds(1));
        worker = worker(repo, transitions, steps, props, clock, crash, random);
      }
      List<Order> now = orders.stream().map(o -> repo.findById(o.id()).orElseThrow()).toList();
      if (now.stream().allMatch(o -> o.state().isTerminal())) {
        if (sc.refundCompleted() && !refundsRequested) {
          refundsRequested = true;
          now.stream()
              .filter(o -> o.state() == OrderState.COMPLETED)
              .forEach(o -> transitions.requestRefund("acme", o.id(), clock.instant()));
          continue;
        }
        break;
      }
      clock.advance(Duration.ofMillis(random.nextLong(5_000)));
    }

    checkInvariants(jdbc, repo, orders, payments, inventory, shipping);
    for (Order o : orders) {
      Statistics.label("final state").collect(repo.findById(o.id()).orElseThrow().state());
    }
    Statistics.label("crashes").collect(sc.crashes().size() - pendingCrashes.size());
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
    }
  }
}
