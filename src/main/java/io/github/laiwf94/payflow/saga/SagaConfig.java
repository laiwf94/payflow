package io.github.laiwf94.payflow.saga;

import io.github.laiwf94.payflow.order.OrderRepository;
import io.github.laiwf94.payflow.order.OrderTransitions;
import java.time.Clock;
import java.util.List;
import java.util.SplittableRandom;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the saga worker. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SagaProperties.class)
public class SagaConfig {

  @Bean
  Backoff sagaBackoff(SagaProperties props) {
    return new Backoff(props.backoffBase(), props.backoffCap(), new SplittableRandom());
  }

  @Bean
  SagaWorker sagaWorker(
      OrderRepository repo,
      OrderTransitions transitions,
      List<SagaStep> steps,
      SagaProperties props,
      Clock clock,
      Backoff backoff,
      ObjectProvider<CrashPoints> crash) {
    return new SagaWorker(
        repo,
        transitions,
        steps,
        props,
        clock,
        backoff,
        crash.getIfAvailable(() -> CrashPoints.NONE));
  }
}
