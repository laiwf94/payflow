package io.github.laiwf94.payflow.sim;

import java.time.Duration;

/** The result of a simulated call as the network would deliver it. */
public sealed interface Simulated<T> {

  /** How long to wait before answering, for every outcome. */
  Duration latency();

  record Answered<T>(T value, Duration latency) implements Simulated<T> {}

  record Unavailable<T>(Duration latency) implements Simulated<T> {}

  /**
   * The caller never gets an answer. {@code applied} is what the simulator did, or null if the
   * request was lost before it was applied; only the simulator's own ledger knows which.
   */
  record Unanswered<T>(T applied, Duration latency) implements Simulated<T> {}
}
