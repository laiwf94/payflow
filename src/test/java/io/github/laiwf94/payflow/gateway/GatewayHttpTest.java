package io.github.laiwf94.payflow.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.laiwf94.payflow.money.Money;
import io.github.laiwf94.payflow.order.LineItem;
import io.github.laiwf94.payflow.order.Orders;
import io.github.laiwf94.payflow.order.ShippingAddress;
import io.github.laiwf94.payflow.sim.Fault;
import io.github.laiwf94.payflow.sim.FaultPlan;
import io.github.laiwf94.payflow.sim.Faults;
import io.github.laiwf94.payflow.sim.InventorySimulator;
import io.github.laiwf94.payflow.sim.PaymentProviderSimulator;
import io.github.laiwf94.payflow.sim.ShippingSimulator;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.SplittableRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** The HTTP adapters against the in-process simulators, over real sockets. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "payflow.simulator.answer-delay=700ms",
      "payflow.dependencies.payment.read-timeout=300ms",
      "payflow.dependencies.inventory.read-timeout=300ms",
      "payflow.dependencies.shipping.read-timeout=300ms"
    })
class GatewayHttpTest {

  @Autowired PaymentGateway payments;
  @Autowired InventoryService inventory;
  @Autowired ShippingService shipping;
  @Autowired Faults faults;
  @Autowired PaymentProviderSimulator paymentSim;
  @Autowired InventorySimulator inventorySim;
  @Autowired ShippingSimulator shippingSim;

  @BeforeEach
  void reset() {
    faults.reset(new SplittableRandom(1));
    paymentSim.reset();
    inventorySim.reset();
    shippingSim.reset();
  }

  @Test
  void paymentHappyPath() {
    PaymentInfo auth =
        success(payments.authorize("a-1", "o1", Money.of(2500, "EUR"), "pm_card_ok"));
    assertThat(auth.status()).isEqualTo(PaymentStatus.AUTHORIZED);
    assertThat(auth.currency()).isEqualTo("EUR");
    PaymentInfo cap = success(payments.capture("c-1", auth.id(), 2500));
    assertThat(cap.status()).isEqualTo(PaymentStatus.CAPTURED);
    RefundInfo first = success(payments.refund("r-1", auth.id(), 1000, "refund-a"));
    assertThat(first).isEqualTo(new RefundInfo("re_00000001", auth.id(), "refund-a", 1000));
    assertThat(success(payments.refund("r-1", auth.id(), 1000, "refund-a")))
        .as("a replay is the same refund")
        .isEqualTo(first);
    RefundInfo second = success(payments.refund("r-2", auth.id(), 1500, "refund-b"));
    assertThat(success(payments.findRefunds(auth.id()))).containsExactly(first, second);
    assertThat(success(payments.findByReference("o1")).get(0).netCaptured()).isZero();
    assertThat(payments.refund("r-3", auth.id(), 1, "refund-c"))
        .isInstanceOfSatisfying(
            CallResult.Rejected.class, r -> assertThat(r.code()).isEqualTo("invalid_amount"));
    assertThat(payments.findRefunds("pay_nothing"))
        .isInstanceOfSatisfying(
            CallResult.Rejected.class, r -> assertThat(r.code()).isEqualTo("not_found"));
    assertThat(success(payments.findByReference("o1"))).hasSize(1);
    assertThat(success(payments.findByReference("nothing"))).isEmpty();
  }

  @Test
  void declineAndInvalidStateAreRejections() {
    assertThat(payments.authorize("a", "o1", Money.of(10, "EUR"), "pm_card_expired"))
        .isEqualTo(new CallResult.Rejected<>("expired_card", "the card was declined"));
    PaymentInfo p = success(payments.authorize("b", "o2", Money.of(10, "EUR"), "pm_card_ok"));
    success(payments.voidAuthorization("v", p.id()));
    assertThat(payments.capture("c", p.id(), 10))
        .isInstanceOfSatisfying(
            CallResult.Rejected.class, r -> assertThat(r.code()).isEqualTo("invalid_state"));
  }

  @Test
  void a503IsTransientAndNothingHappened() {
    faults.set("payment.authorize", new FaultPlan(1, 0, 0, Duration.ZERO, List.of()));
    assertThat(payments.authorize("a", "o1", Money.of(10, "EUR"), "pm_card_ok"))
        .isInstanceOf(CallResult.Transient.class);
    assertThat(paymentSim.paymentsFor("o1")).isEmpty();
  }

  @Test
  void aLostAnswerIsUnknownAndTheChargeExists() {
    faults.set(
        "payment.capture", new FaultPlan(0, 0, 0, Duration.ZERO, List.of(Fault.UNKNOWN_AFTER)));
    PaymentInfo p = success(payments.authorize("a", "o1", Money.of(10, "EUR"), "pm_card_ok"));
    assertThat(payments.capture("c", p.id(), 10)).isInstanceOf(CallResult.Unknown.class);
    assertThat(paymentSim.paymentsFor("o1").get(0).status())
        .isEqualTo(PaymentProviderSimulator.Status.CAPTURED);
    assertThat(success(payments.capture("c", p.id(), 10)).status())
        .as("the retry with the same key replays")
        .isEqualTo(PaymentStatus.CAPTURED);
  }

  @Test
  void pathSegmentsFromResponsesAreEncoded() {
    // Tomcat refuses the encoded slash outright; either way the call can't reach another route.
    assertThat(payments.capture("c", "../authorizations?x=1", 10))
        .isInstanceOf(CallResult.Rejected.class);
    assertThat(paymentSim.payments()).isEmpty();
  }

  @Test
  void inventoryReserveAndRelease() {
    List<LineItem> items = List.of(new LineItem("sku-1", 2, 100));
    assertThat(success(inventory.reserve("o1", items))).isEqualTo("o1");
    assertThat(success(inventory.reserve("o1", items))).isEqualTo("o1");
    assertThat(inventorySim.heldFor("sku-1")).isEqualTo(2);
    inventorySim.setStock("sku-2", 0);
    assertThat(inventory.reserve("o2", List.of(new LineItem("sku-2", 1, 100))))
        .isInstanceOfSatisfying(
            CallResult.Rejected.class, r -> assertThat(r.code()).isEqualTo("out_of_stock"));
    assertThat(success(inventory.release("o1"))).isTrue();
    assertThat(success(inventory.release("o1"))).isTrue();
    assertThat(inventorySim.heldFor("sku-1")).isZero();
  }

  @Test
  void inventoryRestockReturnsUnitsAndReplays() {
    assertThat(success(inventory.reserve("o1", List.of(new LineItem("sku-1", 3, 100)))))
        .isEqualTo("o1");
    List<StockLine> one = List.of(new StockLine("sku-1", 1));
    assertThat(success(inventory.restock("o1", "refund-a", one))).isEqualTo("refund-a");
    assertThat(success(inventory.restock("o1", "refund-a", one)))
        .as("a retry replays")
        .isEqualTo("refund-a");
    assertThat(inventorySim.heldFor("sku-1")).isEqualTo(2);
    assertThat(inventory.restock("o1", "refund-a", List.of(new StockLine("sku-1", 2))))
        .isInstanceOfSatisfying(
            CallResult.Rejected.class, r -> assertThat(r.code()).isEqualTo("conflict"));
    assertThat(inventory.restock("o1", "refund-b", List.of(new StockLine("sku-1", 3))))
        .isInstanceOfSatisfying(
            CallResult.Rejected.class,
            r -> {
              assertThat(r.code()).isEqualTo("exceeds_reserved");
              assertThat(r.message()).contains("holds 2 of sku-1");
            });
    assertThat(inventory.restock("o2", "refund-c", one))
        .isInstanceOfSatisfying(
            CallResult.Rejected.class,
            r -> assertThat(r.code()).isEqualTo("reservation_not_found"));
    assertThat(inventory.restock("../o1", "refund-d", one))
        .as("the reservation id is one path segment")
        .isInstanceOf(CallResult.Rejected.class);
    assertThat(inventorySim.restocksOf("o1"))
        .extracting(InventorySimulator.Restock::id)
        .containsExactly("refund-a");
  }

  @Test
  void aLostRestockAnswerIsUnknownAndTheRetryReplaysIt() {
    success(inventory.reserve("o1", List.of(new LineItem("sku-1", 2, 100))));
    long before = inventorySim.stock("sku-1");
    faults.set(
        "inventory.restock", new FaultPlan(0, 0, 0, Duration.ZERO, List.of(Fault.UNKNOWN_AFTER)));
    List<StockLine> one = List.of(new StockLine("sku-1", 1));
    assertThat(inventory.restock("o1", "refund-a", one)).isInstanceOf(CallResult.Unknown.class);
    assertThat(inventorySim.stock("sku-1"))
        .as("applied before the answer was lost")
        .isEqualTo(before + 1);
    assertThat(success(inventory.restock("o1", "refund-a", one))).isEqualTo("refund-a");
    assertThat(inventorySim.stock("sku-1")).as("not returned twice").isEqualTo(before + 1);
    assertThat(inventorySim.heldFor("sku-1")).isEqualTo(1);
  }

  @Test
  void aLostRestockRequestIsUnknownAndNothingMoved() {
    success(inventory.reserve("o1", List.of(new LineItem("sku-1", 2, 100))));
    long before = inventorySim.stock("sku-1");
    faults.set(
        "inventory.restock", new FaultPlan(0, 0, 0, Duration.ZERO, List.of(Fault.UNKNOWN_BEFORE)));
    List<StockLine> two = List.of(new StockLine("sku-1", 2));
    assertThat(inventory.restock("o1", "refund-a", two)).isInstanceOf(CallResult.Unknown.class);
    assertThat(inventorySim.stock("sku-1")).isEqualTo(before);
    assertThat(inventorySim.restocksOf("o1")).isEmpty();
    faults.set("inventory.restock", new FaultPlan(1, 0, 0, Duration.ZERO, List.of()));
    assertThat(inventory.restock("o1", "refund-a", two))
        .as("a 503 is transient")
        .isInstanceOf(CallResult.Transient.class);
    faults.set("inventory.restock", FaultPlan.NONE);
    assertThat(success(inventory.restock("o1", "refund-a", two))).isEqualTo("refund-a");
    assertThat(inventorySim.stock("sku-1")).isEqualTo(before + 2);
    assertThat(inventorySim.heldFor("sku-1")).isZero();
  }

  @Test
  void shippingCreateCancelFind() {
    ShipmentInfo s = success(shipping.create("o1", Orders.ADDRESS));
    assertThat(s.trackingNumber()).startsWith("TRK");
    assertThat(success(shipping.findByReference("o1"))).contains(s);
    assertThat(success(shipping.findByReference("o2"))).isEqualTo(Optional.empty());
    assertThat(success(shipping.cancel(s.id())).cancelled()).isTrue();
    ShippingAddress nowhere = new ShippingAddress("Bob", "1 Road", null, "Town", "00000", "NL");
    assertThat(shipping.create("o3", nowhere))
        .isInstanceOfSatisfying(
            CallResult.Rejected.class,
            r -> assertThat(r.code()).isEqualTo("address_undeliverable"));
    faults.set("shipping.lookup", new FaultPlan(0, 1, 0, Duration.ZERO, List.of()));
    assertThat(shipping.findByReference("o1")).isInstanceOf(CallResult.Unknown.class);
  }

  @SuppressWarnings("unchecked")
  private static <T> T success(CallResult<T> r) {
    assertThat(r).isInstanceOf(CallResult.Success.class);
    return ((CallResult.Success<T>) r).value();
  }
}
