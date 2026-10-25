package io.github.laiwf94.payflow.sim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class FaultsTest {

  private final Faults faults = new Faults(new SplittableRandom(7));

  @Test
  void scriptedFaultsComeFirstThenRates() {
    faults.set(
        "op",
        new FaultPlan(
            0,
            0,
            0,
            Duration.ZERO,
            List.of(Fault.ERROR, Fault.UNKNOWN_BEFORE, Fault.UNKNOWN_AFTER)));
    AtomicInteger applied = new AtomicInteger();
    assertThat(faults.run("op", applied::incrementAndGet))
        .isInstanceOf(Simulated.Unavailable.class);
    assertThat(faults.run("op", applied::incrementAndGet))
        .isEqualTo(new Simulated.Unanswered<>(null, Duration.ZERO));
    assertThat(applied).hasValue(0);
    assertThat(faults.run("op", applied::incrementAndGet))
        .isEqualTo(new Simulated.Unanswered<>(1, Duration.ZERO));
    assertThat(faults.run("op", applied::incrementAndGet))
        .isEqualTo(new Simulated.Answered<>(2, Duration.ZERO));
  }

  @Test
  void ratesAreRoughlyHonoured() {
    faults.set("op", new FaultPlan(0.2, 0.1, 0.3, Duration.ofMillis(5), List.of()));
    Map<Class<?>, Integer> seen = new java.util.HashMap<>();
    Map<Fault, Integer> kinds = new EnumMap<>(Fault.class);
    for (int i = 0; i < 10_000; i++) {
      AtomicInteger applied = new AtomicInteger();
      Simulated<Integer> r = faults.run("op", applied::incrementAndGet);
      seen.merge(r.getClass(), 1, Integer::sum);
      Fault k =
          switch (r) {
            case Simulated.Answered<Integer> a -> Fault.OK;
            case Simulated.Unavailable<Integer> u -> Fault.ERROR;
            case Simulated.Unanswered<Integer> u ->
                u.applied() == null ? Fault.UNKNOWN_BEFORE : Fault.UNKNOWN_AFTER;
          };
      kinds.merge(k, 1, Integer::sum);
      assertThat(r.latency()).isEqualTo(Duration.ofMillis(5));
    }
    assertThat(kinds.get(Fault.ERROR)).isBetween(1800, 2200);
    assertThat(kinds.get(Fault.UNKNOWN_BEFORE)).isBetween(850, 1150);
    assertThat(kinds.get(Fault.UNKNOWN_AFTER)).isBetween(2750, 3250);
    assertThat(kinds.get(Fault.OK)).isBetween(3750, 4250);
  }

  @Test
  void businessErrorsPropagateWhenAnsweredAndVanishWhenNot() {
    faults.set("op", new FaultPlan(0, 0, 0, Duration.ZERO, List.of(Fault.UNKNOWN_AFTER)));
    assertThat(
            faults.<Integer>run(
                "op",
                () -> {
                  throw new SimulatorException(409, "x", "y");
                }))
        .isEqualTo(new Simulated.Unanswered<Integer>(null, Duration.ZERO));
    assertThatThrownBy(
            () ->
                faults.<Integer>run(
                    "op",
                    () -> {
                      throw new SimulatorException(409, "x", "y");
                    }))
        .isInstanceOf(SimulatorException.class);
  }

  @Test
  void resetClearsPlans() {
    faults.set("op", new FaultPlan(1, 0, 0, Duration.ZERO, List.of()));
    assertThat(faults.get("op").errorRate()).isEqualTo(1);
    faults.reset(new SplittableRandom(1));
    assertThat(faults.get("op")).isEqualTo(FaultPlan.NONE);
  }

  @Test
  void planValidation() {
    assertThatThrownBy(() -> new FaultPlan(-0.1, 0, 0, Duration.ZERO, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new FaultPlan(Double.NaN, 0, 0, Duration.ZERO, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new FaultPlan(0.6, 0.3, 0.2, Duration.ZERO, List.of()))
        .hasMessageContaining("add up");
    assertThatThrownBy(() -> new FaultPlan(0, 0, 0, Duration.ofMillis(-1), List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new FaultPlan(0, 0, 0, Duration.ofSeconds(31), List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new FaultPlan(
                    0, 0, 0, Duration.ZERO, java.util.Collections.nCopies(1001, Fault.OK)))
        .hasMessageContaining("limited");
  }
}
