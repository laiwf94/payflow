package io.github.laiwf94.payflow.webhook;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

  /**
   * A claimed delivery, with what sending it needs. A ping and an endpoint's disabled notice have
   * no order: {@code orderId} is null and {@code orderSeq} 0.
   *
   * @param windowStart when its retry window began: when it was created or last replayed
   * @param once tried once and not retried: a ping, or the final notice to an endpoint payflow
   *     disabled
   */
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
      Instant createdAt,
      Instant windowStart,
      boolean once) {}

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

  /**
   * A delivery as the log shows it.
   *
   * @param nextAttemptAt when a pending delivery is next tried, or until when the attempt in flight
   *     holds it; null once it is settled
   * @param replayedAt when it was last replayed, or null
   * @param lastAttempt the latest attempt, or null before the first
   */
  public record Delivery(
      UUID id,
      UUID endpointId,
      UUID eventId,
      String eventType,
      UUID orderId,
      DeliveryFilter.DeliveryState state,
      int attempts,
      Instant nextAttemptAt,
      Instant createdAt,
      Instant replayedAt,
      Attempt lastAttempt) {}

  /** A delivery with the event it carries and every attempt, oldest first. */
  public record Detail(Delivery delivery, String payload, List<Attempt> attempts) {}

  /**
   * What a replay did: how many settled deliveries it made due again, and how many it left alone
   * because they were still pending and will be sent anyway.
   */
  public record Replayed(int replayed, int alreadyPending) {}

  /** A replay covers more deliveries than one replay may; nothing was changed. */
  public static final class TooManyToReplay extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    TooManyToReplay(int max) {
      super(
          "more than "
              + max
              + " deliveries match; replay at most "
              + max
              + " at a time by narrowing created_gte and created_lt");
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
   * Creates a ping to one endpoint, due now, carrying {@code payload}. It belongs to no order, so
   * it waits for nothing and nothing waits for it. Returns its id.
   */
  @Transactional
  public UUID enqueuePing(UUID endpointId, UUID eventId, String payload, Instant now) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO webhook_deliveries (id, endpoint_id, event_id, event_type, payload, state,
                attempts, next_attempt_at, created_at, updated_at)
            VALUES (:id, :endpoint, :event, :type, CAST(:payload AS jsonb), 'PENDING', 0, :now,
                :now, :now)
            """)
        .param("id", id)
        .param("endpoint", endpointId)
        .param("event", eventId)
        .param("type", WebhookEvents.PING)
        .param("payload", payload)
        .param("now", ts(now))
        .update();
    return id;
  }

  /**
   * Queues the final notice that payflow disabled {@code endpointId}: to the endpoint itself,
   * although it is now disabled and whatever its filter says, and to each of the merchant's other
   * live, enabled endpoints whose filter takes {@value WebhookEvents#ENDPOINT_DISABLED}. Returns
   * the number of deliveries queued.
   */
  @Transactional
  public int enqueueDisabledNotice(
      String merchant, UUID endpointId, UUID eventId, String payload, Instant now) {
    return jdbc.sql(
            """
            INSERT INTO webhook_deliveries (id, endpoint_id, event_id, event_type, payload, state,
                attempts, next_attempt_at, created_at, updated_at)
            SELECT gen_random_uuid(), e.id, :event, :type, CAST(:payload AS jsonb), 'PENDING', 0,
                :now, :now, :now
            FROM webhook_endpoints e
            WHERE e.merchant_id = :m AND e.deleted_at IS NULL
              AND (e.id = :endpoint
                   OR (e.state = 'ENABLED'
                       AND ('*' = ANY (e.enabled_events) OR :type = ANY (e.enabled_events))))
            ON CONFLICT (endpoint_id, event_id) DO NOTHING
            """)
        .param("m", merchant)
        .param("endpoint", endpointId)
        .param("event", eventId)
        .param("type", WebhookEvents.ENDPOINT_DISABLED)
        .param("payload", payload)
        .param("now", ts(now))
        .update();
  }

  /** The disabled notice to the endpoint it is about, which is sent although it is disabled. */
  private static final String OWN_NOTICE =
      "(d.event_type = 'webhook_endpoint.disabled'"
          + " AND d.payload->>'endpoint_id' = d.endpoint_id::text)";

  /** A delivery tried once: a ping, or an endpoint's own disabled notice. */
  private static final String ONCE = "(d.event_type = 'ping' OR " + OWN_NOTICE + ")";

  /**
   * Claims up to {@code limit} due deliveries, oldest due first, leasing each until {@code now +
   * lease}. A delivery is due only when its endpoint is live and enabled (or it is the final notice
   * to an endpoint payflow disabled, not replayed since) and no earlier event of the same order is
   * still pending for the same endpoint, being sent or waiting to retry, so each endpoint sees one
   * order's events in the order they happened. Endpoints in {@code skip} are passed over: this
   * instance already has as many of their deliveries in flight as it allows.
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
                  AND (e.state = 'ENABLED' OR (%1$s AND d.replayed_at IS NULL))
                  AND e.deleted_at IS NULL
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
                d.order_id, d.order_seq, d.payload::text AS payload, d.attempts, d.created_at,
                COALESCE(d.replayed_at, d.created_at) AS window_start, %2$s AS once
            """
                .formatted(OWN_NOTICE, ONCE))
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
                    rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                    rs.getObject("window_start", OffsetDateTime.class).toInstant(),
                    rs.getBoolean("once")))
        .list()
        .stream()
        // RETURNING comes back in no particular order; hand them out oldest event first.
        .sorted(
            Comparator.comparing(Claim::createdAt)
                .thenComparing(Claim::orderId, Comparator.nullsFirst(Comparator.naturalOrder()))
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

  /**
   * One page of an endpoint's deliveries, newest first by {@code (created_at, id)}, after the given
   * position when there is one. Each carries its latest attempt. The keyset condition and the order
   * match the {@code (endpoint_id, created_at, id)} index, so a deep page costs what the first
   * does.
   */
  @Transactional(readOnly = true)
  public List<Delivery> list(
      UUID endpointId, DeliveryFilter filter, Instant afterCreatedAt, UUID afterId, int limit) {
    if (limit < 1) {
      throw new IllegalArgumentException("limit must be positive: " + limit);
    }
    Map<String, Object> params = new HashMap<>();
    StringBuilder where = new StringBuilder("d.endpoint_id = :endpoint");
    params.put("endpoint", endpointId);
    filter(filter, where, params);
    if (afterCreatedAt != null) {
      where.append(" AND (d.created_at, d.id) < (:afterCreated, :afterId)");
      params.put("afterCreated", ts(afterCreatedAt));
      params.put("afterId", afterId);
    }
    params.put("limit", limit);
    return jdbc.sql(
            """
            SELECT d.id, d.endpoint_id, d.event_id, d.event_type, d.order_id, d.state,
                d.attempts, d.next_attempt_at, d.created_at, d.replayed_at,
                a.attempted_at, a.latency_ms, a.response_status, a.response_body, a.error
            FROM (SELECT * FROM webhook_deliveries d WHERE %s
                  ORDER BY d.created_at DESC, d.id DESC LIMIT :limit) d
            LEFT JOIN LATERAL (
                SELECT * FROM webhook_attempts a WHERE a.delivery_id = d.id
                ORDER BY a.id DESC LIMIT 1) a ON true
            ORDER BY d.created_at DESC, d.id DESC
            """
                .formatted(where))
        .params(params)
        .query((rs, n) -> delivery(rs))
        .list();
  }

  /** One of an endpoint's deliveries, with its payload and every attempt, oldest first. */
  @Transactional(readOnly = true)
  public Optional<Detail> find(UUID endpointId, UUID deliveryId) {
    return jdbc.sql(
            """
            SELECT d.id, d.endpoint_id, d.event_id, d.event_type, d.order_id, d.state,
                d.attempts, d.next_attempt_at, d.created_at, d.replayed_at,
                d.payload::text AS payload,
                a.attempted_at, a.latency_ms, a.response_status, a.response_body, a.error
            FROM webhook_deliveries d
            LEFT JOIN LATERAL (
                SELECT * FROM webhook_attempts a WHERE a.delivery_id = d.id
                ORDER BY a.id DESC LIMIT 1) a ON true
            WHERE d.id = :id AND d.endpoint_id = :endpoint
            """)
        .param("id", deliveryId)
        .param("endpoint", endpointId)
        .query((rs, n) -> new Detail(delivery(rs), rs.getString("payload"), List.of()))
        .optional()
        .map(
            d ->
                new Detail(
                    d.delivery(),
                    d.payload(),
                    jdbc.sql(
                            """
                            SELECT * FROM webhook_attempts WHERE delivery_id = :id ORDER BY id
                            """)
                        .param("id", deliveryId)
                        .query((rs, n) -> attempt(rs))
                        .list()));
  }

  /**
   * Makes the endpoint's settled deliveries that match {@code filter} due again at once, each with
   * a new retry window starting now. Pending ones are left alone: they will be sent anyway, and one
   * may be in flight. Refuses, changing nothing, when more than {@code max} settled deliveries
   * match.
   *
   * @throws TooManyToReplay when more than {@code max} match
   */
  @Transactional(noRollbackFor = TooManyToReplay.class)
  public Replayed replay(UUID endpointId, DeliveryFilter filter, int max, Instant now) {
    Map<String, Object> params = new HashMap<>();
    StringBuilder where = new StringBuilder("d.endpoint_id = :endpoint");
    params.put("endpoint", endpointId);
    filter(filter, where, params);
    // Lock the settled rows first, so a count and an update can't disagree with a concurrent
    // replay; max + 1 rows are enough to know there are too many.
    List<UUID> settled =
        jdbc.sql(
                """
                SELECT d.id FROM webhook_deliveries d
                WHERE %s AND d.state <> 'PENDING'
                ORDER BY d.created_at, d.id
                LIMIT :max FOR UPDATE
                """
                    .formatted(where))
            .params(params)
            .param("max", max + 1)
            .query(UUID.class)
            .list();
    if (settled.size() > max) {
      throw new TooManyToReplay(max);
    }
    int pending =
        jdbc.sql(
                "SELECT count(*) FROM webhook_deliveries d WHERE %s AND d.state = 'PENDING'"
                    .formatted(where))
            .params(params)
            .query(Integer.class)
            .single();
    if (settled.isEmpty()) {
      return new Replayed(0, pending);
    }
    int replayed =
        jdbc.sql(
                """
                UPDATE webhook_deliveries
                SET state = 'PENDING', next_attempt_at = :now, replayed_at = :now,
                    updated_at = :now
                WHERE id = ANY (:ids) AND state <> 'PENDING'
                """)
            .param("ids", settled.toArray(UUID[]::new))
            .param("now", ts(now))
            .update();
    return new Replayed(replayed, pending);
  }

  private static void filter(DeliveryFilter f, StringBuilder where, Map<String, Object> params) {
    if (!f.states().isEmpty()) {
      where.append(" AND d.state = ANY (:states)");
      params.put("states", f.stateNames());
    }
    if (f.eventId() != null) {
      where.append(" AND d.event_id = :eventId");
      params.put("eventId", f.eventId());
    }
    if (f.createdGte() != null) {
      where.append(" AND d.created_at >= :createdGte");
      params.put("createdGte", ts(f.createdGte()));
    }
    if (f.createdLt() != null) {
      where.append(" AND d.created_at < :createdLt");
      params.put("createdLt", ts(f.createdLt()));
    }
  }

  private static Delivery delivery(ResultSet rs) throws SQLException {
    return new Delivery(
        rs.getObject("id", UUID.class),
        rs.getObject("endpoint_id", UUID.class),
        rs.getObject("event_id", UUID.class),
        rs.getString("event_type"),
        rs.getObject("order_id", UUID.class),
        DeliveryFilter.DeliveryState.valueOf(rs.getString("state")),
        rs.getInt("attempts"),
        instant(rs, "next_attempt_at"),
        instant(rs, "created_at"),
        instant(rs, "replayed_at"),
        rs.getObject("attempted_at") == null ? null : attempt(rs));
  }

  private static Attempt attempt(ResultSet rs) throws SQLException {
    return new Attempt(
        instant(rs, "attempted_at"),
        rs.getInt("latency_ms"),
        rs.getObject("response_status", Integer.class),
        rs.getString("response_body"),
        rs.getString("error"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
    return t == null ? null : t.toInstant();
  }

  private static OffsetDateTime ts(Instant i) {
    return i.atOffset(ZoneOffset.UTC);
  }
}
