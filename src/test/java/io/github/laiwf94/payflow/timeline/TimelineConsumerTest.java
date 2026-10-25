package io.github.laiwf94.payflow.timeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.github.laiwf94.payflow.order.Order;
import io.github.laiwf94.payflow.order.OrderTransitions;
import io.github.laiwf94.payflow.order.Orders;
import io.github.laiwf94.payflow.support.Containers;
import io.github.laiwf94.payflow.support.Db;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {"payflow.timeline.enabled=true", "payflow.outbox.enabled=true"})
// Its relay and consumer keep running while the context is cached; close it so they stop.
@DirtiesContext
class TimelineConsumerTest {

  static final String TOPIC = "payflow.order-events";

  @Autowired KafkaTemplate<String, String> kafka;
  @Autowired TimelineProjection projection;
  @Autowired TimelineConsumer consumer;
  @Autowired OrderTransitions transitions;
  @Autowired JdbcClient jdbc;
  @Autowired MeterRegistry meters;

  @BeforeEach
  void clean() {
    Db.truncateAll(jdbc);
  }

  private static String event(UUID eventId, UUID order, long seq, String state) {
    return """
        {"event_id":"%s","event_type":"order.%s","order_id":"%s","merchant_id":"acme","seq":%d,
         "state":"%s","amount":2500,"currency":"EUR","occurred_at":"2030-01-01T00:00:0%dZ",
         "added_later":"ignored"}
        """
        .formatted(eventId, state.toLowerCase(java.util.Locale.ROOT), order, seq, state, seq);
  }

  private void send(String key, String value, String correlationId) throws Exception {
    ProducerRecord<String, String> r = new ProducerRecord<>(TOPIC, key, value);
    if (correlationId != null) {
      r.headers().add("correlation-id", correlationId.getBytes(StandardCharsets.UTF_8));
    }
    kafka.send(r).get();
  }

  private int timelineRows(UUID order) {
    return jdbc.sql("SELECT count(*) FROM order_timeline WHERE order_id = :o")
        .param("o", order)
        .query(Integer.class)
        .single();
  }

  @Test
  void everyEventDeliveredTwiceIsAppliedOnce() throws Exception {
    UUID order = UUID.randomUUID();
    List<String> events = new ArrayList<>();
    String[] states = {"PENDING", "INVENTORY_RESERVED", "PAYMENT_AUTHORIZED"};
    for (int i = 0; i < 3; i++) {
      events.add(event(UUID.randomUUID(), order, i, states[i]));
    }
    double before = meters.counter("payflow.inbox.duplicates", "consumer", "timeline").count();
    for (String e : events) {
      send(order.toString(), e, "corr-dup");
      send(order.toString(), e, "corr-dup");
    }
    await().atMost(Duration.ofSeconds(20)).until(() -> timelineRows(order) == 3);
    await()
        .atMost(Duration.ofSeconds(10))
        .until(
            () ->
                meters.counter("payflow.inbox.duplicates", "consumer", "timeline").count()
                    >= before + 3);
    assertThat(projection.forOrder("acme", order))
        .extracting(TimelineEntry::state)
        .containsExactly(states);
    assertThat(projection.forOrder("globex", order)).isEmpty();
  }

  @Test
  void malformedRecordsGoToTheDeadLetterTopicAndTheConsumerMovesOn() throws Exception {
    UUID order = UUID.randomUUID();
    send(order.toString(), "not json at all", null);
    send(order.toString(), "{\"event_id\":\"" + UUID.randomUUID() + "\"}", null);
    send(order.toString(), event(UUID.randomUUID(), order, 0, "PENDING"), null);
    await().atMost(Duration.ofSeconds(20)).until(() -> timelineRows(order) == 1);

    Properties p = new Properties();
    p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, Containers.REDPANDA.getBootstrapServers());
    p.put(ConsumerConfig.GROUP_ID_CONFIG, "dlt-" + UUID.randomUUID());
    p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    List<String> dead = new ArrayList<>();
    try (KafkaConsumer<String, String> c = new KafkaConsumer<>(p)) {
      c.subscribe(List.of(TOPIC + "-dlt"));
      long deadline = System.currentTimeMillis() + 20_000;
      while (System.currentTimeMillis() < deadline && dead.size() < 2) {
        for (ConsumerRecord<String, String> r : c.poll(Duration.ofMillis(300))) {
          if (order.toString().equals(r.key())) {
            dead.add(r.value());
          }
        }
      }
    }
    assertThat(dead).hasSize(2).contains("not json at all");
  }

  @Test
  void outboxToKafkaToTimeline() {
    Order o = Orders.pending("acme", Instant.now());
    transitions.create(o);
    await().atMost(Duration.ofSeconds(20)).until(() -> timelineRows(o.id()) == 1);
    assertThat(projection.forOrder("acme", o.id()))
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.eventType()).isEqualTo("order.created");
              assertThat(e.state()).isEqualTo("PENDING");
              assertThat(e.seq()).isZero();
            });
  }

  @Test
  void parsingRejectsWhatCanNeverBeApplied() {
    assertThatThrownBy(() -> consumer.parse(null)).isInstanceOf(MalformedEventException.class);
    assertThatThrownBy(() -> consumer.parse("null")).isInstanceOf(MalformedEventException.class);
    assertThatThrownBy(() -> consumer.parse("[1]")).isInstanceOf(MalformedEventException.class);
  }

  @Test
  void inboxCleanupKeepsRecentEntries() {
    UUID order = UUID.randomUUID();
    projection.apply(consumer.parse(event(UUID.randomUUID(), order, 0, "PENDING")));
    jdbc.sql(
            "INSERT INTO inbox (consumer, event_id, received_at) "
                + "VALUES ('timeline', gen_random_uuid(), now() - interval '30 days')")
        .update();
    assertThat(projection.cleanupInbox()).isEqualTo(1);
    assertThat(jdbc.sql("SELECT count(*) FROM inbox").query(Integer.class).single()).isEqualTo(1);
  }
}
