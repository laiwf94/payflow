package io.github.laiwf94.payflow.webhook;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Webhook deliveries and their attempts in Postgres.
 *
 * <p>A delivery is claimed by bumping its {@code attempts} and pushing {@code next_attempt_at} out
 * by a lease. The attempt number is the fence: an outcome is written only while the delivery is
 * still pending at the attempt number its claim took, so a worker whose lease ran out and was
 * claimed again can't overwrite the newer attempt.
 */
@Repository
public class WebhookDeliveryRepository {

  /** A claimed delivery, with what sending it needs. */
  public record Claim(
      UUID id,
      UUID endpointId,
      String merchantId,
      String url,
      UUID eventId,
      String eventType,
      UUID orderId,
      long orderSeq,
      String payload,
      int attempt,
      Instant createdAt) {}

  /**
   * One attempt as the log keeps it.
   *
   * @param status the HTTP status, or null when there was none
   * @param body the start of the answer's body, at most 1,024 characters
   * @param error why there was no HTTP status, or null when there was one
   */
  public record Attempt(
      Instant attemptedAt, int latencyMs, Integer status, String body, String error) {

    public Attempt {
      if ((status == null) == (error == null)) {
        throw new IllegalArgumentException("an attempt has a status or an error, not both");
      }
      body = body == null ? null : clean(body, 1024);
      error = error == null ? null : clean(error, 512);
      latencyMs = Math.max(0, latencyMs);
    }

    /** Postgres text can't hold NUL; anything longer than the column is cut. */
    private static String clean(String s, int max) {
      String t = s.replace('\0', '�');
      return t.length() <= max ? t : t.substring(0, max);
    }
  }

  private final JdbcClient jdbc;

  public WebhookDeliveryRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Creates a pending delivery, due now, of each of these outbox events to each enabled endpoint of
   * the event's merchant whose filter takes its type. An event already delivered to an endpoint is
   * skipped, so an event relayed twice is delivered once. Returns the number created.
   */
  @Transactional
  public int enqueue(Collection<Long> outboxIds, Instant now) {
    if (outboxIds.isEmpty()) {
      return 0;
    }
    return jdbc.sql(
            """
            INSERT INTO webhook_deliveries (id, endpoint_id, event_id, event_type, order_id,
                order_seq, payload, state, attempts, next_attempt_at, created_at, updated_at)
            SELECT gen_random_uuid(), e.id, o.event_id, o.event_type, o.aggregate_id,
                o.aggregate_seq, o.payload, 'PENDING', 0, :now, :now, :now
            FROM outbox o
            JOIN webhook_endpoints e
              ON e.merchant_id = o.payload->>'merchant_id'
             AND e.deleted_at IS NULL AND e.state = 'ENABLED'
             AND ('*' = ANY (e.enabled_events) OR o.event_type = ANY (e.enabled_events))
            WHERE o.id = ANY (:ids)
            ORDER BY o.aggregate_id, o.aggregate_seq, e.id
            ON CONFLICT (endpoint_id, event_id) DO NOTHING
            """)
        .param("ids", outboxIds.toArray(Long[]::new))
        .param("now", ts(now))
        .update();
  }

  /**
   * Claims up to {@code limit} due deliveries, oldest due first, leasing each until {@code now +
   * lease}. A delivery is due only when its endpoint is live and enabled and no earlier event of
   * the same order is still pending for the same endpoint, being sent or waiting to retry, so each
   * endpoint sees one order's events in the order they happened. Endpoints in {@code skip} are
   * passed over: this instance already has as many of their deliveries in flight as it allows.
   */
  @Transactional
  public List<Claim> claimDue(Instant now, Duration lease, int limit, Collection<UUID> skip) {
    return jdbc
        .sql(
            """
            WITH due AS (
                SELECT d.id FROM webhook_deliveries d
                JOIN webhook_endpoints e ON e.id = d.endpoint_id
                WHERE d.state = 'PENDING' AND d.next_attempt_at <= :now
                  AND e.state = 'ENABLED' AND e.deleted_at IS NULL
                  AND d.endpoint_id <> ALL (:skip)
                  AND NOT EXISTS (
                      SELECT 1 FROM webhook_deliveries p
                      WHERE p.endpoint_id = d.endpoint_id AND p.order_id = d.order_id
                        AND p.order_seq < d.order_seq AND p.state = 'PENDING')
                ORDER BY d.next_attempt_at, d.id
                LIMIT :limit
                FOR UPDATE OF d SKIP LOCKED)
            UPDATE webhook_deliveries d
            SET attempts = d.attempts + 1, next_attempt_at = :leaseUntil, updated_at = :now
            FROM due, webhook_endpoints e
            WHERE d.id = due.id AND e.id = d.endpoint_id
            RETURNING d.id, d.endpoint_id, e.merchant_id, e.url, d.event_id, d.event_type,
                d.order_id, d.order_seq, d.payload::text AS payload, d.attempts, d.created_at
            """)
        .param("now", ts(now))
        .param("leaseUntil", ts(now.plus(lease)))
        .param("limit", limit)
        .param("skip", skip.toArray(UUID[]::new))
        .query(
            (rs, n) ->
                new Claim(
                    rs.getObject("id", UUID.class),
                    rs.getObject("endpoint_id", UUID.class),
                    rs.getString("merchant_id"),
                    rs.getString("url"),
                    rs.getObject("event_id", UUID.class),
                    rs.getString("event_type"),
                    rs.getObject("order_id", UUID.class),
                    rs.getLong("order_seq"),
                    rs.getString("payload"),
                    rs.getInt("attempts"),
                    rs.getObject("created_at", OffsetDateTime.class).toInstant()))
        .list()
        .stream()
        // RETURNING comes back in no particular order; hand them out oldest event first.
        .sorted(
            Comparator.comparing(Claim::createdAt)
                .thenComparing(Claim::orderId)
                .thenComparingLong(Claim::orderSeq))
        .toList();
  }

  /**
   * Gives back a claim that was never tried: the attempt it took is returned and the delivery is
   * due again at once. False when the claim no longer holds.
   */
  @Transactional
  public boolean unclaim(Claim c, Instant now) {
    return jdbc.sql(
                """
                UPDATE webhook_deliveries
                SET attempts = attempts - 1, next_attempt_at = :now, updated_at = :now
                WHERE id = :id AND state = 'PENDING' AND attempts = :attempt
                """)
            .param("id", c.id())
            .param("attempt", c.attempt())
            .param("now", ts(now))
            .update()
        == 1;
  }

  /**
   * Records a successful attempt and settles the delivery. False when the claim no longer holds.
   */
  @Transactional
  public boolean succeed(Claim c, Attempt a, Instant now) {
    return settle(c, a, "SUCCEEDED", null, now);
  }

  /** Records a failed attempt and schedules the next. False when the claim no longer holds. */
  @Transactional
  public boolean retry(Claim c, Attempt a, Instant next, Instant now) {
    return settle(c, a, "PENDING", next, now);
  }

  /** Records the last failed attempt and gives the delivery up. False when the claim is lost. */
  @Transactional
  public boolean giveUp(Claim c, Attempt a, Instant now) {
    return settle(c, a, "FAILED", null, now);
  }

  private boolean settle(Claim c, Attempt a, String state, Instant next, Instant now) {
    int n =
        jdbc.sql(
                """
                UPDATE webhook_deliveries
                SET state = :state, next_attempt_at = :next, updated_at = :now
                WHERE id = :id AND state = 'PENDING' AND attempts = :attempt
                """)
            .param("id", c.id())
            .param("attempt", c.attempt())
            .param("state", state)
            .param("next", next == null ? null : ts(next))
            .param("now", ts(now))
            .update();
    if (n == 0) {
      return false;
    }
    jdbc.sql(
            """
            INSERT INTO webhook_attempts (delivery_id, attempted_at, latency_ms, response_status,
                response_body, error)
            VALUES (:id, :at, :latency, :status, :body, :error)
            """)
        .param("id", c.id())
        .param("at", ts(a.attemptedAt()))
        .param("latency", a.latencyMs())
        .param("status", a.status())
        .param("body", a.body())
        .param("error", a.error())
        .update();
    return true;
  }

  /** Gives up every pending delivery to an endpoint, which is being deleted. */
  public int failPending(UUID endpointId, Instant now) {
    return jdbc.sql(
            """
            UPDATE webhook_deliveries SET state = 'FAILED', next_attempt_at = NULL, updated_at = :now
            WHERE endpoint_id = :id AND state = 'PENDING'
            """)
        .param("id", endpointId)
        .param("now", ts(now))
        .update();
  }

  private static OffsetDateTime ts(Instant i) {
    return i.atOffset(ZoneOffset.UTC);
  }
}
