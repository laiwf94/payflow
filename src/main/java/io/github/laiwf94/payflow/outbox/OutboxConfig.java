package io.github.laiwf94.payflow.outbox;

import io.github.laiwf94.payflow.saga.CrashPoints;
import java.time.Clock;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Wires the outbox relay and declares the events topic. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxConfig {

  @Bean
  NewTopic orderEventsTopic(OutboxProperties props) {
    return new NewTopic(props.topic(), props.partitions(), props.replicas());
  }

  @Bean
  OutboxRelay outboxRelay(
      JdbcClient jdbc,
      TransactionTemplate tx,
      KafkaTemplate<String, String> kafka,
      OutboxProperties props,
      OutboxSignal signal,
      Clock clock,
      ObjectProvider<CrashPoints> crash) {
    return new OutboxRelay(
        jdbc, tx, kafka, props, signal, clock, crash.getIfAvailable(() -> CrashPoints.NONE));
  }
}
