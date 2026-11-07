package io.github.laiwf94.payflow.refund;

import io.github.laiwf94.payflow.order.OrderRepository;
import io.github.laiwf94.payflow.saga.Backoff;
import io.github.laiwf94.payflow.saga.CrashPoints;
import io.github.laiwf94.payflow.saga.SagaProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the refund worker, on the saga worker's settings and backoff. */
@Configuration(proxyBeanMethods = false)
public class RefundConfig {

  @Bean
  RefundWorker refundWorker(
      RefundRepository refunds,
      OrderRepository orders,
      RefundTransitions transitions,
      RefundAttempt attempt,
      SagaProperties props,
      Clock clock,
      Backoff backoff,
      ObjectProvider<CrashPoints> crash,
      MeterRegistry meters) {
    return new RefundWorker(
        refunds,
        orders,
        transitions,
        attempt,
        props,
        clock,
        backoff,
        crash.getIfAvailable(() -> CrashPoints.NONE),
        meters);
  }
}
