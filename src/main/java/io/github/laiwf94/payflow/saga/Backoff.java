package io.github.laiwf94.payflow.saga;

import java.time.Duration;
import java.util.random.RandomGenerator;

/**
 * Exponential backoff with full jitter: attempt {@code n} waits a uniform random time in {@code [0,
 * min(cap, base * 2^n)]}. Spreading retries over the whole window, rather than around a fixed
 * delay, keeps a fleet of workers from retrying in lockstep after a shared outage.
 */
public final class Backoff {

  private final long baseMillis;
  private final long capMillis;
  private final RandomGenerator random;

  public Backoff(Duration base, Duration cap, RandomGenerator random) {
    if (base.isNegative() || base.isZero() || cap.compareTo(base) < 0) {
      throw new IllegalArgumentException("backoff needs 0 < base <= cap");
    }
    this.baseMillis = base.toMillis();
    this.capMillis = cap.toMillis();
    this.random = random;
  }

  /** The upper bound for attempt {@code attempt} (0-based). */
  public long ceilingMillis(int attempt) {
    int shift = Math.min(Math.max(attempt, 0), 40);
    long ceiling = baseMillis << shift;
    return ceiling <= 0 || ceiling > capMillis ? capMillis : ceiling;
  }

  public Duration delay(int attempt) {
    return Duration.ofMillis(random.nextLong(ceilingMillis(attempt) + 1));
  }
}
