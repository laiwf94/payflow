package io.github.laiwf94.payflow.sim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.laiwf94.payflow.sim.ShippingSimulator.Shipment;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class ShippingSimulatorTest {

  private final ShippingSimulator sim = new ShippingSimulator(new Faults(new SplittableRandom(1)));

  private static Shipment answered(Simulated<Shipment> s) {
    return ((Simulated.Answered<Shipment>) s).value();
  }

  @Test
  void onePerReferenceAndCancellable() {
    Shipment a = answered(sim.create("o1", "12345", "NL"));
    assertThat(answered(sim.create("o1", "12345", "NL"))).isEqualTo(a);
    assertThat(a.trackingNumber()).startsWith("TRK");
    Shipment c = answered(sim.cancel(a.id()));
    assertThat(c.status()).isEqualTo(ShippingSimulator.Status.CANCELLED);
    assertThat(answered(sim.cancel(a.id())).status()).isEqualTo(ShippingSimulator.Status.CANCELLED);
    assertThat(sim.shipment("o1")).contains(c);
    assertThat(((Simulated.Answered<?>) sim.findByReference("o2")).value())
        .isEqualTo(java.util.Optional.empty());
    assertThat(sim.shipments()).hasSize(1);
  }

  @Test
  void rejectsUndeliverableAddressesAndBadInput() {
    assertThatThrownBy(() -> sim.create("o1", ShippingSimulator.UNDELIVERABLE_POSTAL_CODE, "NL"))
        .satisfies(e -> assertThat(((SimulatorException) e).status()).isEqualTo(422));
    assertThatThrownBy(() -> sim.create("", "1", "NL")).isInstanceOf(SimulatorException.class);
    assertThatThrownBy(() -> sim.create("o", null, "NL")).isInstanceOf(SimulatorException.class);
    assertThatThrownBy(() -> sim.cancel("shp_missing"))
        .satisfies(e -> assertThat(((SimulatorException) e).status()).isEqualTo(404));
  }

  @Test
  void resetRestartsNumbering() {
    answered(sim.create("o1", "1", "NL"));
    sim.reset();
    assertThat(answered(sim.create("o2", "1", "NL")).id()).isEqualTo("shp_00000001");
  }
}
