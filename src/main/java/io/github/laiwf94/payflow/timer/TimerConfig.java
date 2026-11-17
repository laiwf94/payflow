package io.github.laiwf94.payflow.timer;

import io.github.laiwf94.payflow.saga.Backoff;
import io.github.laiwf94.payflow.saga.CrashPoints;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.List;
import java.util.SplittableRandom;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/** Wires durable timers: the service that schedules them and the worker that fires them. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TimerProperties.class)
public class TimerConfig {

  @Bean
  TimerRepository timerRepository(JdbcClient jdbc) {
    return new TimerRepository(jdbc);
  }

  /** Every {@link TimerHandler} bean; startup fails on two for one kind. */
  @Bean
  TimerHandlers timerHandlers(List<TimerHandler> handlers) {
    return new TimerHandlers(handlers);
  }

  @Bean
  TimerSignal timerSignal() {
    return new TimerSignal();
  }

  @Bean
  TimerService timerService(
      TimerRepository timers,
      TimerHandlers handlers,
      TimerSignal signal,
      Clock clock,
      TimerProperties props) {
    return new TimerService(timers, handlers, signal, clock, props.pollInterval());
  }

  @Bean
  TimerWorker timerWorker(
      TimerRepository timers,
      TimerHandlers handlers,
      TimerProperties props,
      TimerSignal signal,
      PlatformTransactionManager txManager,
      Clock clock,
      ObjectProvider<CrashPoints> crash,
      MeterRegistry meters) {
    return new TimerWorker(
        timers,
        handlers,
        props,
        signal,
        txManager,
        clock,
        new Backoff(props.retryBase(), props.retryCap(), new SplittableRandom()),
        crash.getIfAvailable(() -> CrashPoints.NONE),
        meters);
  }
}
