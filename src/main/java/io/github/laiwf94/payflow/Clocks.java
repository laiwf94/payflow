package io.github.laiwf94.payflow;

import java.time.Clock;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The one clock everything schedules against, replaced in tests to move time by hand. */
@Configuration(proxyBeanMethods = false)
public class Clocks {

  /**
   * Ticks in whole microseconds, the precision Postgres stores. The system clock can return
   * nanoseconds, and an instant returned in a response before it is stored would then differ from
   * every later read of the same row.
   */
  @Bean
  Clock clock() {
    return systemMicros();
  }

  static Clock systemMicros() {
    return Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));
  }
}
