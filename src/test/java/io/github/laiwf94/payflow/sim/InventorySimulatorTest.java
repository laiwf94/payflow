package io.github.laiwf94.payflow.sim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.laiwf94.payflow.sim.InventorySimulator.Line;
import java.time.Duration;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class InventorySimulatorTest {

  private final Faults faults = new Faults(new SplittableRandom(1));
  private final InventorySimulator inv = new InventorySimulator(faults, 10);

  @Test
  void reserveIsAllOrNothingAndIdempotent() {
    inv.setStock("b", 1);
    assertThatThrownBy(() -> inv.reserve("r1", List.of(new Line("a", 3), new Line("b", 2))))
        .satisfies(e -> assertThat(((SimulatorException) e).code()).isEqualTo("out_of_stock"));
    assertThat(inv.stock("a")).as("nothing taken on failure").isEqualTo(10);

    List<Line> lines = List.of(new Line("a", 3), new Line("a", 2), new Line("b", 1));
    inv.reserve("r1", lines);
    inv.reserve("r1", lines);
    assertThat(inv.stock("a")).isEqualTo(5);
    assertThat(inv.stock("b")).isZero();
    assertThat(inv.heldFor("a")).isEqualTo(5);
    assertThatThrownBy(() -> inv.reserve("r1", List.of(new Line("a", 1))))
        .satisfies(e -> assertThat(((SimulatorException) e).code()).isEqualTo("conflict"));
  }

  @Test
  void releaseReturnsStockAndIsSafeToRepeat() {
    inv.reserve("r1", List.of(new Line("a", 4)));
    assertThat(((Simulated.Answered<Boolean>) inv.release("r1")).value()).isTrue();
    assertThat(((Simulated.Answered<Boolean>) inv.release("r1")).value()).isFalse();
    assertThat(inv.stock("a")).isEqualTo(10);
    assertThat(inv.initialStock("a")).isEqualTo(10);
    assertThat(inv.reservations()).isEmpty();
  }

  @Test
  void lookupAndStockOverride() {
    inv.reserve("r1", List.of(new Line("a", 4)));
    assertThat(((Simulated.Answered<?>) inv.find("r1")).value())
        .isNotEqualTo(java.util.Optional.empty());
    assertThat(inv.reservation("nope")).isEmpty();
    inv.setStock("a", 2);
    assertThat(inv.initialStock("a")).as("held units count toward the starting stock").isEqualTo(6);
    assertThatThrownBy(() -> inv.setStock("a", -1)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void unknownAfterReservesWithoutTellingTheCaller() {
    faults.set(
        "inventory.reserve", new FaultPlan(0, 0, 0, Duration.ZERO, List.of(Fault.UNKNOWN_AFTER)));
    assertThat(inv.reserve("r1", List.of(new Line("a", 1))))
        .isInstanceOf(Simulated.Unanswered.class);
    assertThat(inv.reservation("r1")).isPresent();
  }

  @Test
  void validatesInput() {
    assertThatThrownBy(() -> inv.reserve("", List.of(new Line("a", 1))))
        .isInstanceOf(SimulatorException.class);
    assertThatThrownBy(() -> inv.reserve("r", List.of())).isInstanceOf(SimulatorException.class);
    assertThatThrownBy(() -> inv.reserve("r", null)).isInstanceOf(SimulatorException.class);
    assertThatThrownBy(() -> inv.reserve("r", List.of(new Line("a", 0))))
        .isInstanceOf(SimulatorException.class);
    assertThatThrownBy(() -> inv.reserve("x".repeat(129), List.of(new Line("a", 1))))
        .isInstanceOf(SimulatorException.class);
  }

  @Test
  void resetForgetsStock() {
    inv.setStock("a", 0);
    inv.reset();
    assertThat(inv.stock("a")).isEqualTo(10);
  }
}
