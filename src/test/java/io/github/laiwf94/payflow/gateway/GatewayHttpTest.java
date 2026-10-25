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
import io.github.laiwf94.payflow.support.TestInfrastructure;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.SplittableRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** The HTTP adapters against the in-process simulators, over real sockets. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "payflow.simulator.answer-delay=700ms",
      "payflow.dependencies.payment.read-timeout=300ms",
      "payflow.dependencies.shipping.read-timeout=300ms"
    })
@Import(TestInfrastructure.class)
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
    PaymentInfo ref = success(payments.refund("r-1", auth.id(), 2500));
    assertThat(ref.netCaptured()).isZero();
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
