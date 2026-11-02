package io.github.laiwf94.payflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;

class ClocksTest {

  @Test
  void theApplicationClockTicksInWholeMicroseconds() {
    Clock clock = Clocks.systemMicros();
    assertThat(clock.getZone()).isEqualTo(java.time.ZoneOffset.UTC);
    for (int i = 0; i < 10_000; i++) {
      Instant t = clock.instant();
      assertThat(t).isEqualTo(t.truncatedTo(ChronoUnit.MICROS));
    }
  }
}
