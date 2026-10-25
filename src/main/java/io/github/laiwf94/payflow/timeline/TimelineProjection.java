package io.github.laiwf94.payflow.timeline;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Builds and reads the order timeline from order events, deduplicating through the inbox. */
@Component
public class TimelineProjection {

  static final String CONSUMER = "timeline";

  /** Longer than the outbox keeps events, so no redelivery can outlive its inbox entry. */
  static final Duration INBOX_RETENTION = Duration.ofDays(14);

  private final JdbcClient jdbc;
  private final Clock clock;
  private final Counter duplicates;

  public TimelineProjection(JdbcClient jdbc, Clock clock, MeterRegistry meters) {
    this.jdbc = jdbc;
    this.clock = clock;
    this.duplicates = meters.counter("payflow.inbox.duplicates", "consumer", CONSUMER);
  }

  /** Applies the event once. Returns false when it was already applied. */
  @Transactional
  public boolean apply(OrderEvent e) {
    OffsetDateTime now = clock.instant().atOffset(ZoneOffset.UTC);
    int fresh =
        jdbc.sql(
                """
                INSERT INTO inbox (consumer, event_id, received_at) VALUES (:consumer, :id, :now)
                ON CONFLICT DO NOTHING
                """)
            .param("consumer", CONSUMER)
            .param("id", e.eventId())
            .param("now", now)
            .update();
    if (fresh == 0) {
      duplicates.increment();
      return false;
    }
    jdbc.sql(
            """
            INSERT INTO order_timeline (order_id, merchant_id, seq, event_id, event_type, state,
                failure_code, occurred_at, recorded_at)
            VALUES (:order, :merchant, :seq, :event, :type, :state, :failure, :occurred, :now)
            ON CONFLICT DO NOTHING
            """)
        .param("order", e.orderId())
        .param("merchant", e.merchantId())
        .param("seq", e.seq())
        .param("event", e.eventId())
        .param("type", e.eventType())
        .param("state", e.state())
        .param("failure", e.failureCode())
        .param("occurred", e.occurredAt())
        .param("now", now)
        .update();
    return true;
  }

  /** The order's events in sequence, for one merchant. */
  public List<TimelineEntry> forOrder(String merchantId, UUID orderId) {
    return jdbc.sql(
            """
            SELECT seq, event_type, state, failure_code, occurred_at FROM order_timeline
            WHERE order_id = :order AND merchant_id = :merchant
            ORDER BY seq
            LIMIT 1000
            """)
        .param("order", orderId)
        .param("merchant", merchantId)
        .query(
            (rs, i) ->
                new TimelineEntry(
                    rs.getLong("seq"),
                    rs.getString("event_type"),
                    rs.getString("state"),
                    rs.getString("failure_code"),
                    rs.getObject("occurred_at", OffsetDateTime.class).toInstant()))
        .list();
  }

  @Scheduled(fixedDelay = 1, timeUnit = java.util.concurrent.TimeUnit.HOURS)
  public int cleanupInbox() {
    return jdbc.sql(
            """
            DELETE FROM inbox WHERE (consumer, event_id) IN (
                SELECT consumer, event_id FROM inbox WHERE received_at < :cutoff LIMIT 10000)
            """)
        .param("cutoff", clock.instant().minus(INBOX_RETENTION).atOffset(ZoneOffset.UTC))
        .update();
  }
}
