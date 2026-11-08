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
  void restockReturnsUnitsOnceAndNeverMoreThanIsHeld() {
    inv.reserve("r1", List.of(new Line("a", 3), new Line("b", 1), new Line("a", 1)));
    assertThat(inv.stock("a")).isEqualTo(6);

    Simulated<InventorySimulator.Restock> first =
        inv.restock("r1", "x1", List.of(new Line("a", 3)));
    assertThat(((Simulated.Answered<InventorySimulator.Restock>) first).value())
        .isEqualTo(new InventorySimulator.Restock("x1", "r1", List.of(new Line("a", 3))));
    inv.restock("r1", "x1", List.of(new Line("a", 3)));
    assertThat(inv.stock("a")).as("a replay returns nothing more").isEqualTo(9);
    assertThat(inv.heldFor("a")).isEqualTo(1);
    assertThat(inv.stock("a") + inv.heldFor("a")).isEqualTo(inv.initialStock("a"));

    assertThatThrownBy(() -> inv.restock("r1", "x1", List.of(new Line("a", 1))))
        .satisfies(e -> assertThat(((SimulatorException) e).code()).isEqualTo("conflict"));
    assertThatThrownBy(() -> inv.restock("r2", "x1", List.of(new Line("a", 3))))
        .as("the same id on another reservation")
        .satisfies(e -> assertThat(((SimulatorException) e).code()).isEqualTo("conflict"));
    assertThatThrownBy(() -> inv.restock("r1", "x2", List.of(new Line("b", 1), new Line("a", 2))))
        .as("all or nothing: b fits, a does not")
        .satisfies(
            e -> {
              assertThat(((SimulatorException) e).status()).isEqualTo(409);
              assertThat(((SimulatorException) e).code()).isEqualTo("exceeds_reserved");
              assertThat(e.getMessage()).contains("holds 1 of a");
            });
    assertThat(inv.stock("b")).isEqualTo(9);
    assertThatThrownBy(() -> inv.restock("r1", "x2", List.of(new Line("c", 1))))
        .as("a SKU the reservation never held")
        .satisfies(e -> assertThat(((SimulatorException) e).code()).isEqualTo("exceeds_reserved"));
    assertThatThrownBy(() -> inv.restock("nope", "x3", List.of(new Line("a", 1))))
        .satisfies(
            e -> {
              assertThat(((SimulatorException) e).status()).isEqualTo(404);
              assertThat(((SimulatorException) e).code()).isEqualTo("reservation_not_found");
            });

    inv.restock("r1", "x2", List.of(new Line("b", 1), new Line("a", 1)));
    assertThat(inv.heldFor("a")).isZero();
    assertThat(inv.heldFor("b")).isZero();
    assertThat(inv.restocksOf("r1"))
        .extracting(InventorySimulator.Restock::id)
        .containsExactly("x1", "x2");
    assertThat(
            ((Simulated.Answered<List<InventorySimulator.Restock>>) inv.findRestocks("r1")).value())
        .hasSize(2);
    assertThat(inv.restocksOf("nope")).isEmpty();
    assertThat(inv.reservation("r1")).as("the reservation itself is unchanged").isPresent();
    assertThat(inv.reserve("r1", List.of(new Line("a", 3), new Line("b", 1), new Line("a", 1))))
        .as("so a reserve retry still replays")
        .isInstanceOf(Simulated.Answered.class);
  }

  @Test
  void releaseAfterARestockReturnsOnlyWhatIsStillHeld() {
    inv.reserve("r1", List.of(new Line("a", 4)));
    inv.restock("r1", "x1", List.of(new Line("a", 1)));
    inv.release("r1");
    assertThat(inv.stock("a")).isEqualTo(10);
    assertThat(inv.heldFor("a")).isZero();
    assertThat(inv.restocksOf("r1")).as("restocks go with the reservation").isEmpty();

    inv.reserve("r1", List.of(new Line("a", 2)));
    inv.restock("r1", "x1", List.of(new Line("a", 2)));
    assertThat(inv.stock("a")).as("a new reservation starts with nothing restocked").isEqualTo(10);
    inv.reset();
    assertThat(inv.restocksOf("r1")).isEmpty();
  }

  @Test
  void unknownAfterRestocksWithoutTellingTheCaller() {
    inv.reserve("r1", List.of(new Line("a", 2)));
    faults.set(
        "inventory.restock",
        new FaultPlan(0, 0, 0, Duration.ZERO, List.of(Fault.UNKNOWN_AFTER, Fault.UNKNOWN_BEFORE)));
    assertThat(inv.restock("r1", "x1", List.of(new Line("a", 1))))
        .isInstanceOf(Simulated.Unanswered.class);
    assertThat(inv.stock("a")).isEqualTo(9);
    assertThat(inv.restock("r1", "x2", List.of(new Line("a", 1))))
        .isInstanceOf(Simulated.Unanswered.class);
    assertThat(inv.stock("a")).as("lost on the way in: nothing moved").isEqualTo(9);
    assertThat(inv.restocksOf("r1"))
        .extracting(InventorySimulator.Restock::id)
        .containsExactly("x1");
  }

  @Test
  void validatesInput() {
    assertThatThrownBy(() -> inv.restock("r", "", List.of(new Line("a", 1))))
        .isInstanceOf(SimulatorException.class);
    assertThatThrownBy(() -> inv.restock("", "x", List.of(new Line("a", 1))))
        .isInstanceOf(SimulatorException.class);
    assertThatThrownBy(() -> inv.restock("r", "x", List.of()))
        .isInstanceOf(SimulatorException.class);
    assertThatThrownBy(() -> inv.restock("r", "x", List.of(new Line(null, 1))))
        .isInstanceOf(SimulatorException.class);
    assertThatThrownBy(() -> inv.restock("r", "x", List.of(new Line("a", -1))))
        .isInstanceOf(SimulatorException.class);
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
