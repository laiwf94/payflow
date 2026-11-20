package io.github.laiwf94.payflow.sim;

import java.time.Duration;

/** Global simulator switches that can change at runtime through the control API. */
public final class SimulatorSettings {

  private volatile boolean honorIdempotencyKeys = true;

  /** Seven days: what the card networks give an online card payment. */
  public static final Duration DEFAULT_AUTHORIZATION_LIFETIME = Duration.ofDays(7);

  /** Longer than any card network keeps a hold. */
  static final Duration MAX_AUTHORIZATION_LIFETIME = Duration.ofDays(31);

  private volatile Duration answerDelay;
  private volatile Duration authorizationLifetime;
  private final Duration configuredAuthorizationLifetime;

  public SimulatorSettings(Duration answerDelay) {
    this(answerDelay, DEFAULT_AUTHORIZATION_LIFETIME);
  }

  public SimulatorSettings(Duration answerDelay, Duration authorizationLifetime) {
    this.answerDelay = answerDelay;
    authorizationLifetime(authorizationLifetime);
    this.configuredAuthorizationLifetime = authorizationLifetime;
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

  /**
   * How long an authorization, or a re-authorization, holds the money before it lapses: the {@code
   * expires_at} the provider advertises on it.
   */
  public Duration authorizationLifetime() {
    return authorizationLifetime;
  }

  public void authorizationLifetime(Duration lifetime) {
    if (lifetime.compareTo(Duration.ofMillis(1)) < 0
        || lifetime.compareTo(MAX_AUTHORIZATION_LIFETIME) > 0) {
      throw new IllegalArgumentException("authorization lifetime must be between 1 ms and 31 days");
    }
    this.authorizationLifetime = lifetime;
  }

  /** Back to the lifetime this simulator was configured with. */
  public void resetAuthorizationLifetime() {
    this.authorizationLifetime = configuredAuthorizationLifetime;
  }
}
