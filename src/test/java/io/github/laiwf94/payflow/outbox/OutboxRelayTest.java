package io.github.laiwf94.payflow.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.laiwf94.payflow.order.Order;
import io.github.laiwf94.payflow.order.OrderRepository;
import io.github.laiwf94.payflow.order.OrderState;
import io.github.laiwf94.payflow.order.OrderTransitions;
import io.github.laiwf94.payflow.order.Orders;
import io.github.laiwf94.payflow.order.StateChange;
import io.github.laiwf94.payflow.saga.CrashPoints;
import io.github.laiwf94.payflow.support.Containers;
import io.github.laiwf94.payflow.support.Db;
import io.github.laiwf94.payflow.support.MutableClock;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** The outbox against real Postgres and Redpanda. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class OutboxRelayTest {

  @Autowired JdbcClient jdbc;
  @Autowired TransactionTemplate tx;
  @Autowired KafkaTemplate<String, String> kafka;
  @Autowired OutboxSignal signal;
  @Autowired OrderRepository repo;
  @Autowired OrderTransitions transitions;
  @Autowired OutboxWriter writer;
  @Autowired ObjectMapper json;

  private final MutableClock clock = new MutableClock(Instant.now());
  private String topic;

  @BeforeEach
  void setUp() throws Exception {
    Db.truncateAll(jdbc);
    topic = "test-events-" + UUID.randomUUID();
    // Created up front: letting the first send auto-create it races the consumer's subscription.
    try (AdminClient admin =
        AdminClient.create(
            Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                Containers.REDPANDA.getBootstrapServers()))) {
      admin.createTopics(List.of(new NewTopic(topic, 3, (short) 1))).all().get();
    }
  }

  private OutboxProperties props() {
    return new OutboxProperties(
        true,
        topic,
        3,
        (short) 1,
        Duration.ofMillis(10),
        7,
        Duration.ofSeconds(10),
        Duration.ofDays(7));
  }

  private OutboxRelay relay(CrashPoints crash) {
    return new OutboxRelay(
        jdbc,
        tx,
        kafka,
        props(),
        signal,
        clock,
        crash,
        new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
  }

  /** Writes {@code n} events for one order through the real writer, one transaction each. */
  private Order eventsFor(int n) {
    Order o = Orders.pending("acme", clock.instant());
    transitions.create(o);
    for (int seq = 1; seq < n; seq++) {
      Order bumped = withVersion(o, seq);
      tx.executeWithoutResult(s -> writer.write("order.test", bumped, null, clock.instant()));
    }
    return o;
  }

  private static Order withVersion(Order o, long v) {
    return new Order(
        o.id(),
        o.merchantId(),
        o.state(),
        o.amount(),
        o.items(),
        o.shippingAddress(),
        o.paymentMethod(),
        o.cancelRequested(),
        o.reservationId(),
        o.paymentId(),
        o.shipmentId(),
        o.trackingNumber(),
        o.capturedMinor(),
        o.refundedMinor(),
        o.failureCode(),
        o.failureMessage(),
        o.attempt(),
        o.stepStartedAt(),
        o.nextAttemptAt(),
        o.correlationId(),
        v,
        o.createdAt(),
        o.updatedAt());
  }

  private List<ConsumerRecord<String, String>> consumeAll(int atLeast) {
    Properties p = new Properties();
    p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, Containers.REDPANDA.getBootstrapServers());
    p.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
    p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    List<ConsumerRecord<String, String>> out = new ArrayList<>();
    try (KafkaConsumer<String, String> c = new KafkaConsumer<>(p)) {
      c.subscribe(List.of(topic));
      long deadline = System.currentTimeMillis() + 20_000;
      int quiet = 0;
      while (System.currentTimeMillis() < deadline && (out.size() < atLeast || quiet < 3)) {
        int before = out.size();
        c.poll(Duration.ofMillis(300)).forEach(out::add);
        quiet = out.size() == before ? quiet + 1 : 0;
      }
    }
    return out;
  }

  private long seq(ConsumerRecord<String, String> r) {
    try {
      JsonNode n = json.readTree(r.value());
      return n.get("seq").asLong();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static String header(ConsumerRecord<String, String> r, String name) {
    return new String(r.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
  }

  private int unpublished() {
    return jdbc.sql("SELECT count(*) FROM outbox WHERE published_at IS NULL")
        .query(Integer.class)
        .single();
  }

  @Test
  void threeConcurrentRelaysKeepEachOrdersEventsInSequence() throws Exception {
    for (int i = 0; i < 40; i++) {
      eventsFor(5);
    }
    List<Thread> threads = new ArrayList<>();
    for (int t = 0; t < 3; t++) {
      OutboxRelay r = relay(CrashPoints.NONE);
      threads.add(
          Thread.ofPlatform()
              .start(
                  () -> {
                    while (r.relayOnce() > 0) {
                      Thread.onSpinWait();
                    }
                  }));
    }
    for (Thread t : threads) {
      t.join();
    }
    assertThat(unpublished()).isZero();

    List<ConsumerRecord<String, String>> records = consumeAll(200);
    assertThat(records).hasSize(200);
    Map<String, List<Long>> perOrder = new HashMap<>();
    for (ConsumerRecord<String, String> r : records) {
      perOrder.computeIfAbsent(r.key(), k -> new ArrayList<>()).add(seq(r));
      assertThat(header(r, OutboxRelay.HEADER_CORRELATION_ID)).startsWith("corr-");
      assertThat(header(r, OutboxRelay.HEADER_EVENT_ID)).isNotBlank();
    }
    assertThat(perOrder).hasSize(40);
    perOrder.values().forEach(seqs -> assertThat(seqs).containsExactly(0L, 1L, 2L, 3L, 4L));
  }

  @Test
  void aCrashAfterSendingRepublishesButNeverSkips() {
    Order o = eventsFor(3);
    OutboxRelay crashing =
        relay(
            p -> {
              if (p.equals("relay:sent")) {
                throw new IllegalStateException("process died before commit");
              }
            });
    assertThatThrownBy(crashing::relayOnce).hasMessageContaining("died");
    assertThat(unpublished()).isEqualTo(3);
    assertThat(relay(CrashPoints.NONE).relayOnce()).isEqualTo(3);

    List<ConsumerRecord<String, String>> records = consumeAll(6);
    assertThat(records).hasSize(6);
    Map<String, Long> firstSeen = new LinkedHashMap<>();
    for (ConsumerRecord<String, String> r : records) {
      firstSeen.putIfAbsent(header(r, OutboxRelay.HEADER_EVENT_ID), seq(r));
      assertThat(r.key()).isEqualTo(o.id().toString());
    }
    assertThat(firstSeen.values()).as("deduplicated by event id").containsExactly(0L, 1L, 2L);
  }

  @Test
  void whenKafkaIsDownNothingIsMarkedPublished() {
    eventsFor(2);
    Map<String, Object> cfg = new HashMap<>();
    cfg.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:1");
    cfg.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    cfg.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    cfg.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 300);
    DefaultKafkaProducerFactory<String, String> factory = new DefaultKafkaProducerFactory<>(cfg);
    KafkaTemplate<String, String> down = new KafkaTemplate<>(factory);
    OutboxRelay r =
        new OutboxRelay(
            jdbc,
            tx,
            down,
            new OutboxProperties(
                true,
                topic,
                1,
                (short) 1,
                Duration.ofMillis(10),
                10,
                Duration.ofSeconds(1),
                Duration.ofDays(1)),
            signal,
            clock,
            CrashPoints.NONE,
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    try {
      assertThatThrownBy(r::relayOnce).isInstanceOf(RuntimeException.class);
      assertThat(unpublished()).isEqualTo(2);
    } finally {
      factory.destroy();
    }
  }

  @Test
  void stateChangesAndTheirEventsCommitTogether() {
    Order o = Orders.pending("acme", clock.instant());
    transitions.create(o);
    var claim = repo.claimDue(clock.instant(), Duration.ofSeconds(30), 1).get(0);
    transitions.apply(
        o,
        claim.leaseToken(),
        StateChange.to(OrderState.INVENTORY_RESERVED)
            .stepStartedAt(clock.instant())
            .nextAttemptAt(clock.instant())
            .keepLeaseUntil(clock.instant().plusSeconds(30))
            .build(),
        clock.instant());
    // A retry in the same state is not an event.
    Order reserved = repo.findById(o.id()).orElseThrow();
    transitions.apply(
        reserved,
        claim.leaseToken(),
        StateChange.to(OrderState.INVENTORY_RESERVED)
            .attempt(1)
            .nextAttemptAt(clock.instant())
            .build(),
        clock.instant());
    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    s -> {
                      transitions.requestCancel("acme", o.id(), clock.instant());
                      throw new IllegalStateException("rolled back");
                    }))
        .hasMessage("rolled back");
    List<String> types =
        jdbc.sql("SELECT event_type FROM outbox WHERE aggregate_id = :id ORDER BY aggregate_seq")
            .param("id", o.id())
            .query(String.class)
            .list();
    assertThat(types).containsExactly("order.created", "order.inventory_reserved");
    assertThat(repo.findById(o.id()).orElseThrow().cancelRequested()).isFalse();
  }

  @Test
  void theWriterRefusesToRunOutsideATransaction() {
    Order o = Orders.pending("acme", clock.instant());
    assertThatThrownBy(() -> writer.write("order.created", o, null, clock.instant()))
        .hasMessageContaining("transaction");
  }

  @Test
  void cleanupDeletesOnlyOldPublishedEvents() {
    eventsFor(2);
    relay(CrashPoints.NONE).relayOnce();
    eventsFor(1);
    OutboxRelay r = relay(CrashPoints.NONE);
    assertThat(r.cleanup()).isZero();
    clock.advance(Duration.ofDays(8));
    assertThat(r.cleanup()).isEqualTo(2);
    assertThat(unpublished()).isEqualTo(1);
  }

  @Test
  void backgroundRelayPublishesAfterAWakeUp() {
    OutboxRelay r = relay(CrashPoints.NONE);
    r.start();
    try {
      assertThat(r.isRunning()).isTrue();
      eventsFor(4);
      org.awaitility.Awaitility.await()
          .atMost(Duration.ofSeconds(15))
          .until(() -> unpublished() == 0);
    } finally {
      r.stop();
    }
    assertThat(r.isRunning()).isFalse();
    Set<Long> seqs = new HashSet<>();
    consumeAll(4).forEach(rec -> seqs.add(seq(rec)));
    assertThat(seqs).containsExactlyInAnyOrder(0L, 1L, 2L, 3L);
  }

  @Test
  void backlogGaugesTrackUnpublishedEvents() {
    var meters = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    OutboxRelay r =
        new OutboxRelay(jdbc, tx, kafka, props(), signal, clock, CrashPoints.NONE, meters);
    eventsFor(3);
    clock.advance(Duration.ofSeconds(42));
    r.refreshBacklog();
    assertThat(meters.get("payflow.outbox.pending").gauge().value()).isEqualTo(3);
    assertThat(meters.get("payflow.outbox.oldest.age").gauge().value()).isEqualTo(42);
    r.relayOnce();
    r.refreshBacklog();
    assertThat(meters.get("payflow.outbox.pending").gauge().value()).isZero();
    assertThat(meters.get("payflow.outbox.published").counter().count()).isEqualTo(3);
  }

  @Test
  void propertiesAreValidated() {
    assertThatThrownBy(
            () ->
                new OutboxProperties(
                    true, "t", 1, (short) 1, Duration.ZERO, 0, Duration.ZERO, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
