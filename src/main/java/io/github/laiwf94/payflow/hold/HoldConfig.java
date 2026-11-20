package io.github.laiwf94.payflow.hold;

import io.github.laiwf94.payflow.order.CancelReason;
import io.github.laiwf94.payflow.order.OrderRepository;
import io.github.laiwf94.payflow.outbox.OutboxWriter;
import io.github.laiwf94.payflow.saga.SagaWorker;
import io.github.laiwf94.payflow.timer.TimerService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the time limits on holds: the timers that track unpaid orders and waiting authorizations,
 * and the handlers that fire.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(HoldProperties.class)
public class HoldConfig {

  @Bean
  OrderHolds orderHolds(TimerService timers, HoldProperties props) {
    return new OrderHolds(timers, props);
  }

  @Bean
  ExpiryHandler reservationExpiryHandler(
      OrderRepository orders,
      OutboxWriter outbox,
      ObjectProvider<SagaWorker> saga,
      Clock clock,
      MeterRegistry meters) {
    return new ExpiryHandler(
        OrderHolds.RESERVATION_EXPIRY,
        CancelReason.RESERVATION_EXPIRED,
        orders,
        outbox,
        saga,
        clock,
        meters);
  }

  @Bean
  ExpiryHandler paymentExpiryHandler(
      OrderRepository orders,
      OutboxWriter outbox,
      ObjectProvider<SagaWorker> saga,
      Clock clock,
      MeterRegistry meters) {
    return new ExpiryHandler(
        OrderHolds.PAYMENT_EXPIRY,
        CancelReason.PAYMENT_EXPIRED,
        orders,
        outbox,
        saga,
        clock,
        meters);
  }

  @Bean
  ReauthorizationHandler reauthorizationHandler(
      OrderRepository orders, ObjectProvider<SagaWorker> saga, Clock clock, MeterRegistry meters) {
    return new ReauthorizationHandler(orders, saga, clock, meters);
  }
}
