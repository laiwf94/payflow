package io.github.laiwf94.payflow.outbox;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Outbox relay settings ({@code payflow.outbox.*}).
 *
 * @param enabled run the background relay
 * @param topic Kafka topic for order events
 * @param partitions partitions to create the topic with
 * @param replicas replication factor to create the topic with
 * @param pollInterval idle wait between polls
 * @param batchSize orders (queue heads) claimed per poll
 * @param sendTimeout how long to wait for Kafka to acknowledge a batch; the outbox rows stay locked
 *     meanwhile
 * @param retention how long published events are kept before cleanup
 */
@ConfigurationProperties("payflow.outbox")
public record OutboxProperties(
    @DefaultValue("true") boolean enabled,
    @DefaultValue("payflow.order-events") String topic,
    @DefaultValue("6") int partitions,
    @DefaultValue("1") short replicas,
    @DefaultValue("50ms") Duration pollInterval,
    @DefaultValue("100") int batchSize,
    @DefaultValue("10s") Duration sendTimeout,
    @DefaultValue("7d") Duration retention) {

  public OutboxProperties {
    if (batchSize < 1 || batchSize > 1000) {
      throw new IllegalArgumentException("outbox batch size must be 1-1000");
    }
  }
}
