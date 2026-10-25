package io.github.laiwf94.payflow.sim;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Fault settings for one operation. Scripted faults are consumed first, in order; after that each
 * call draws from the rates. Rates are probabilities in [0, 1] and their sum must not exceed 1.
 */
public record FaultPlan(
    double errorRate,
    double unknownBeforeRate,
    double unknownAfterRate,
    Duration latency,
    List<Fault> script) {

  /** Latency above this would tie up simulator threads for no extra realism. */
  public static final Duration MAX_LATENCY = Duration.ofSeconds(30);

  public static final int MAX_SCRIPT = 1000;

  // Declared after the limits above: static fields initialise in order and the constructor reads
  // them.
  public static final FaultPlan NONE = new FaultPlan(0, 0, 0, Duration.ZERO, List.of());

  public FaultPlan {
    Objects.requireNonNull(latency, "latency");
    script = List.copyOf(script);
    for (double r : new double[] {errorRate, unknownBeforeRate, unknownAfterRate}) {
      if (!(r >= 0 && r <= 1)) {
        throw new IllegalArgumentException("rates must be between 0 and 1");
      }
    }
    if (errorRate + unknownBeforeRate + unknownAfterRate > 1.0 + 1e-9) {
      throw new IllegalArgumentException("rates must add up to at most 1");
    }
    if (latency.isNegative() || latency.compareTo(MAX_LATENCY) > 0) {
      throw new IllegalArgumentException("latency must be between 0 and " + MAX_LATENCY);
    }
    if (script.size() > MAX_SCRIPT) {
      throw new IllegalArgumentException("script is limited to " + MAX_SCRIPT + " entries");
    }
  }
}
