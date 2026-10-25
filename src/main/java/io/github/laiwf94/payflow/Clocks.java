package io.github.laiwf94.payflow;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The one clock everything schedules against, replaced in tests to move time by hand. */
@Configuration(proxyBeanMethods = false)
public class Clocks {

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
