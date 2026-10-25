package io.github.laiwf94.payflow.outbox;

import io.github.laiwf94.payflow.saga.CrashPoints;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publishes outbox rows to Kafka, at least once and in order per order id.
 *
 * <p>Each poll locks the <em>head</em> of each order's queue (its oldest unpublished event) with
 * {@code FOR UPDATE SKIP LOCKED}, then publishes that order's whole backlog in sequence. Another
 * relay can't pick a later event of the same order meanwhile: the {@code NOT EXISTS} makes only
 * heads eligible, and the head is locked and still unpublished. Several relays can run side by side
 * without reordering anything.
 *
 * <p>The row locks are held while Kafka acknowledges, bounded by {@code sendTimeout}. If the send
 * or the commit fails the rows stay unpublished and go out again: duplicates are possible, gaps are
 * not. Consumers deduplicate by event id.
 */
public class OutboxRelay implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

  static final String HEADER_EVENT_ID = "event-id";
  static final String HEADER_EVENT_TYPE = "event-type";
  public static final String HEADER_CORRELATION_ID = "correlation-id";

  private record Row(
      long id,
      UUID eventId,
      UUID aggregateId,
      long seq,
      String type,
      String payload,
      String correlationId) {}

  private final JdbcClient jdbc;
  private final TransactionTemplate tx;
  private final KafkaTemplate<String, String> kafka;
  private final OutboxProperties props;
  private final OutboxSignal signal;
  private final Clock clock;
  private final CrashPoints crash;
  private volatile boolean running;
  private Thread thread;
  private Instant lastCleanup = Instant.EPOCH;

  public OutboxRelay(
      JdbcClient jdbc,
      TransactionTemplate tx,
      KafkaTemplate<String, String> kafka,
      OutboxProperties props,
      OutboxSignal signal,
      Clock clock,
      CrashPoints crash) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.kafka = kafka;
    this.props = props;
    this.signal = signal;
    this.clock = clock;
    this.crash = crash;
  }

  /** Publishes one batch. Returns the number of events published. */
  public int relayOnce() {
    Integer n = tx.execute(status -> publishBatch());
    return n == null ? 0 : n;
  }

  private int publishBatch() {
    List<UUID> heads =
        jdbc.sql(
                """
                SELECT o.aggregate_id FROM outbox o
                WHERE o.published_at IS NULL
                  AND NOT EXISTS (
                      SELECT 1 FROM outbox p
                      WHERE p.aggregate_id = o.aggregate_id
                        AND p.published_at IS NULL
                        AND p.aggregate_seq < o.aggregate_seq)
                ORDER BY o.id
                LIMIT :limit
                FOR UPDATE SKIP LOCKED
                """)
            .param("limit", props.batchSize())
            .query(UUID.class)
            .list();
    if (heads.isEmpty()) {
      return 0;
    }
    List<Row> rows =
        jdbc.sql(
                """
                SELECT id, event_id, aggregate_id, aggregate_seq, event_type, payload::text AS payload,
                       correlation_id
                FROM outbox
                WHERE published_at IS NULL AND aggregate_id = ANY(:heads)
                ORDER BY aggregate_id, aggregate_seq
                """)
            .param("heads", heads.toArray(UUID[]::new))
            .query(
                (rs, i) ->
                    new Row(
                        rs.getLong("id"),
                        rs.getObject("event_id", UUID.class),
                        rs.getObject("aggregate_id", UUID.class),
                        rs.getLong("aggregate_seq"),
                        rs.getString("event_type"),
                        rs.getString("payload"),
                        rs.getString("correlation_id")))
            .list();
    List<CompletableFuture<?>> sends = new ArrayList<>(rows.size());
    for (Row r : rows) {
      ProducerRecord<String, String> record =
          new ProducerRecord<>(props.topic(), r.aggregateId().toString(), r.payload());
      record.headers().add(HEADER_EVENT_ID, bytes(r.eventId().toString()));
      record.headers().add(HEADER_EVENT_TYPE, bytes(r.type()));
      record.headers().add(HEADER_CORRELATION_ID, bytes(r.correlationId()));
      sends.add(kafka.send(record));
    }
    try {
      CompletableFuture.allOf(sends.toArray(CompletableFuture[]::new))
          .get(props.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while publishing", e);
    } catch (ExecutionException | TimeoutException e) {
      throw new IllegalStateException("Kafka did not acknowledge the batch", e);
    }
    crash.at("relay:sent");
    jdbc.sql("UPDATE outbox SET published_at = :now WHERE id = ANY(:ids)")
        .param("now", clock.instant().atOffset(ZoneOffset.UTC))
        .param("ids", rows.stream().map(Row::id).toArray(Long[]::new))
        .update();
    return rows.size();
  }

  /** Deletes published events older than the retention, a bounded batch at a time. */
  public int cleanup() {
    return jdbc.sql(
            """
            DELETE FROM outbox WHERE id IN (
                SELECT id FROM outbox WHERE published_at < :cutoff LIMIT 10000)
            """)
        .param("cutoff", clock.instant().minus(props.retention()).atOffset(ZoneOffset.UTC))
        .update();
  }

  private void loop() {
    while (running) {
      try {
        int n = relayOnce();
        Instant now = clock.instant();
        if (now.isAfter(lastCleanup.plusSeconds(60))) {
          lastCleanup = now;
          cleanup();
        }
        if (n == 0) {
          signal.await(props.pollInterval());
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (RuntimeException e) {
        // Kafka or Postgres is unavailable. Events wait in the outbox; orders keep flowing.
        log.warn("outbox relay failed, will retry: {}", e.getMessage());
        try {
          signal.await(props.pollInterval().multipliedBy(20));
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    }
  }

  private static byte[] bytes(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  @Override
  public void start() {
    if (!props.enabled() || running) {
      return;
    }
    running = true;
    thread = Thread.ofPlatform().daemon().name("outbox-relay").start(this::loop);
  }

  @Override
  public void stop() {
    running = false;
    signal.wakeUp();
    if (thread != null) {
      try {
        thread.join(props.sendTimeout().toMillis() + 1000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  @Override
  public boolean isRunning() {
    return running;
  }
}
