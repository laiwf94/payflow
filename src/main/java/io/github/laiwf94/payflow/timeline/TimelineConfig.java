package io.github.laiwf94.payflow.timeline;

import io.github.laiwf94.payflow.outbox.OutboxProperties;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.backoff.FixedBackOff;

/** Error handling for the consumer: a few retries, then the dead-letter topic. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class TimelineConfig {

  /** Same partition count as the source: the recoverer keeps each record's partition. */
  @Bean
  NewTopic orderEventsDeadLetterTopic(OutboxProperties props) {
    return new NewTopic(props.topic() + "-dlt", props.partitions(), props.replicas());
  }

  @Bean
  CommonErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafka) {
    // A database outage is retried here and, once retries run out, the record is parked on the
    // dead-letter topic rather than lost; it can be replayed from there.
    DefaultErrorHandler handler =
        new DefaultErrorHandler(new DeadLetterPublishingRecoverer(kafka), new FixedBackOff(500, 5));
    handler.addNotRetryableExceptions(MalformedEventException.class);
    return handler;
  }
}
