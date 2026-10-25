package io.github.laiwf94.payflow.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.SplittableRandom;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Test;

class BackoffTest {

  @Property
  void delayStaysWithinTheJitterWindow(
      @ForAll @LongRange(min = 1, max = 10_000) long baseMs,
      @ForAll @LongRange(min = 0, max = 100_000) long extraCapMs,
      @ForAll @IntRange(min = -1, max = 200) int attempt,
      @ForAll long seed) {
    Backoff b =
        new Backoff(
            Duration.ofMillis(baseMs),
            Duration.ofMillis(baseMs + extraCapMs),
            new SplittableRandom(seed));
    double ceiling =
        Math.min((double) (baseMs + extraCapMs), baseMs * Math.pow(2, Math.max(attempt, 0)));
    long d = b.delay(attempt).toMillis();
    assertThat(d).isBetween(0L, (long) ceiling);
    assertThat(b.ceilingMillis(attempt)).isEqualTo((long) ceiling);
    assertThat(b.ceilingMillis(attempt + 1)).isGreaterThanOrEqualTo(b.ceilingMillis(attempt));
  }

  @Test
  void spreadsOverTheWholeWindow() {
    Backoff b =
        new Backoff(Duration.ofMillis(100), Duration.ofSeconds(10), new SplittableRandom(1));
    long max = 0;
    long min = Long.MAX_VALUE;
    for (int i = 0; i < 1000; i++) {
      long d = b.delay(3).toMillis();
      max = Math.max(max, d);
      min = Math.min(min, d);
    }
    assertThat(min).isLessThan(50);
    assertThat(max).isGreaterThan(750);
  }

  @Test
  void rejectsBadBounds() {
    SplittableRandom r = new SplittableRandom();
    assertThatThrownBy(() -> new Backoff(Duration.ZERO, Duration.ofSeconds(1), r))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Backoff(Duration.ofSeconds(2), Duration.ofSeconds(1), r))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
