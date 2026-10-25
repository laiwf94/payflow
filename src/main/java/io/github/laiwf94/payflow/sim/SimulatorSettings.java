package io.github.laiwf94.payflow.sim;

import java.time.Duration;

/** Global simulator switches that can change at runtime through the control API. */
public final class SimulatorSettings {

  private volatile boolean honorIdempotencyKeys = true;
  private volatile Duration answerDelay;

  public SimulatorSettings(Duration answerDelay) {
    this.answerDelay = answerDelay;
  }

  public boolean honorIdempotencyKeys() {
    return honorIdempotencyKeys;
  }

  public void honorIdempotencyKeys(boolean honor) {
    this.honorIdempotencyKeys = honor;
  }

  /**
   * How long an unanswered call holds the connection before giving up. It has to exceed the
   * client's read timeout, so the client sees a timeout and not an error status.
   */
  public Duration answerDelay() {
    return answerDelay;
  }

  public void answerDelay(Duration delay) {
    if (delay.isNegative() || delay.compareTo(FaultPlan.MAX_LATENCY) > 0) {
      throw new IllegalArgumentException("answer delay must be between 0 and 30 s");
    }
    this.answerDelay = delay;
  }
}
