package io.github.laiwf94.payflow.refund;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Refunds in Postgres. Like an order's saga, every write by the refund worker is a compare-and-set
 * on {@code (id, lease_token)} with the refund still {@code PENDING}, so a worker whose lease was
 * taken over can't settle a refund twice. The money rules (refunds within the capture and within
 * the goods bought) are the database's: see {@code V9__refunds.sql}.
 */
@Repository
public class RefundRepository {

  private static final TypeReference<List<RefundLine>> LINES = new TypeReference<>() {};

  private final JdbcClient jdbc;
  private final ObjectMapper json;

  public RefundRepository(JdbcClient jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  public void insert(Refund r) {
    jdbc.sql(
            """
            INSERT INTO refunds (id, order_id, amount_minor, lines, state, attempt,
                next_attempt_at, created_at, updated_at)
            VALUES (:id, :order, :amount, CAST(:lines AS jsonb), :state, :attempt,
                :nextAttemptAt, :createdAt, :updatedAt)
            """)
        .param("id", r.id())
        .param("order", r.orderId())
        .param("amount", r.amountMinor())
        .param("lines", r.lines().isEmpty() ? null : write(r.lines()))
        .param("state", r.state().name())
        .param("attempt", r.attempt())
        .param("nextAttemptAt", ts(r.nextAttemptAt()))
        .param("createdAt", ts(r.createdAt()))
        .param("updatedAt", ts(r.updatedAt()))
        .update();
  }

  public Optional<Refund> findById(UUID id) {
    return jdbc.sql("SELECT * FROM refunds WHERE id = :id")
        .param("id", id)
        .query((rs, n) -> map(rs))
        .optional();
  }

  /** An order's refunds, oldest first. */
  public List<Refund> listForOrder(UUID orderId) {
    return jdbc.sql("SELECT * FROM refunds WHERE order_id = :order ORDER BY created_at, id")
        .param("order", orderId)
        .query((rs, n) -> map(rs))
        .list();
  }

  /**
   * The {@link RefundSummary} of each of {@code orderIds}; orders without refunds are left out. Two
   * statements read it, so a caller that needs it to agree with the orders reads both in one
   * snapshot or under the orders' locks. Each order's newest refunds come from the {@code
   * refunds_order} index, however many it has.
   */
  public Map<UUID, RefundSummary> summaries(Collection<UUID> orderIds) {
    if (orderIds.isEmpty()) {
      return Map.of();
    }
    Map<UUID, List<Refund>> recent = new HashMap<>();
    jdbc.sql(
            """
            SELECT r.* FROM orders o
            CROSS JOIN LATERAL (
                SELECT * FROM refunds WHERE order_id = o.id
                ORDER BY created_at DESC, id DESC
                LIMIT :limit) r
            WHERE o.id IN (:ids)
            ORDER BY r.order_id, r.created_at DESC, r.id DESC
            """)
        .param("ids", orderIds)
        .param("limit", RefundSummary.RECENT + 1)
        .query(
            (RowCallbackHandler)
                rs -> {
                  Refund r = map(rs);
                  recent.computeIfAbsent(r.orderId(), k -> new ArrayList<>()).add(r);
                });
    Map<UUID, Long> pending = new HashMap<>();
    jdbc.sql(
            """
            SELECT order_id, sum(amount_minor) AS pending FROM refunds
            WHERE order_id IN (:ids) AND state = 'PENDING'
            GROUP BY order_id
            """)
        .param("ids", orderIds)
        .query(
            (RowCallbackHandler)
                rs -> pending.put(rs.getObject("order_id", UUID.class), rs.getLong("pending")));
    Map<UUID, RefundSummary> out = new HashMap<>();
    recent.forEach(
        (order, rows) -> {
          boolean more = rows.size() > RefundSummary.RECENT;
          out.put(
              order,
              new RefundSummary(
                  more ? rows.subList(0, RefundSummary.RECENT) : rows,
                  more,
                  pending.getOrDefault(order, 0L)));
        });
    return out;
  }

  /**
   * What an order's refunds that haven't failed add up to: the money already returned or promised
   * back. Exact only while the order is locked (see {@code OrderRepository#lockForRefund}).
   */
  public long activeTotal(UUID orderId) {
    return jdbc.sql(
            """
            SELECT coalesce(sum(amount_minor), 0) FROM refunds
            WHERE order_id = :order AND state <> 'FAILED'
            """)
        .param("order", orderId)
        .query(Long.class)
        .single();
  }

  /**
   * How many units of each sku an order's refunds that haven't failed give back. Exact only while
   * the order is locked, like {@link #activeTotal}.
   */
  public Map<String, Long> activeLineQuantities(UUID orderId) {
    Map<String, Long> out = new HashMap<>();
    jdbc.sql(
            """
            SELECT l ->> 'sku' AS sku, sum((l ->> 'quantity')::bigint) AS quantity
            FROM refunds, jsonb_array_elements(lines) AS l
            WHERE order_id = :order AND state <> 'FAILED' AND lines IS NOT NULL
            GROUP BY 1
            """)
        .param("order", orderId)
        .query((RowCallbackHandler) rs -> out.put(rs.getString("sku"), rs.getLong("quantity")));
    return out;
  }

  /**
   * Leases up to {@code limit} due refunds. {@code SKIP LOCKED} lets every instance poll without
   * blocking on the others; the lease, not the row lock, protects a refund while the provider is
   * asked, because no transaction stays open across that call.
   */
  public List<RefundClaim> claimDue(Instant now, Duration lease, int limit) {
    return jdbc.sql(
            """
            WITH due AS (
                SELECT id, lease_token AS expired_token
                FROM refunds
                WHERE next_attempt_at <= :now
                  AND (lease_until IS NULL OR lease_until < :now)
                ORDER BY next_attempt_at
                LIMIT :limit
                FOR UPDATE SKIP LOCKED
            )
            UPDATE refunds r
            SET lease_token = gen_random_uuid(), lease_until = :leaseUntil
            FROM due
            WHERE r.id = due.id
            RETURNING r.*, due.expired_token IS NOT NULL AS recovered
            """)
        .param("now", ts(now))
        .param("leaseUntil", ts(now.plus(lease)))
        .param("limit", limit)
        .query(
            (rs, n) ->
                new RefundClaim(
                    map(rs), rs.getObject("lease_token", UUID.class), rs.getBoolean("recovered")))
        .list();
  }

  /** Settles a leased refund as made. Empty when the lease was lost. */
  public Optional<Refund> succeed(UUID id, UUID leaseToken, String providerRefundId, Instant now) {
    return settle(id, leaseToken, RefundState.SUCCEEDED, providerRefundId, null, null, now);
  }

  /** Settles a leased refund as refused. Empty when the lease was lost. */
  public Optional<Refund> fail(UUID id, UUID leaseToken, String code, String message, Instant now) {
    return settle(id, leaseToken, RefundState.FAILED, null, code, message, now);
  }

  private Optional<Refund> settle(
      UUID id,
      UUID leaseToken,
      RefundState state,
      String providerRefundId,
      String failureCode,
      String failureMessage,
      Instant now) {
    return jdbc.sql(
            """
            UPDATE refunds SET state = :state,
                provider_refund_id = COALESCE(CAST(:provider AS text), provider_refund_id),
                failure_code = :failureCode, failure_message = :failureMessage,
                next_attempt_at = NULL, lease_token = NULL, lease_until = NULL, updated_at = :now
            WHERE id = :id AND lease_token = :token AND state = 'PENDING'
            RETURNING *
            """)
        .param("state", state.name())
        .param("provider", providerRefundId)
        .param("failureCode", failureCode)
        .param("failureMessage", failureMessage)
        .param("now", ts(now))
        .param("id", id)
        .param("token", leaseToken)
        .query((rs, n) -> map(rs))
        .optional();
  }

  /** Schedules the next try and gives the lease back. False when the lease was lost. */
  public boolean retry(UUID id, UUID leaseToken, int attempt, Instant nextAttemptAt, Instant now) {
    return jdbc.sql(
                """
                UPDATE refunds SET attempt = :attempt, next_attempt_at = :next,
                    lease_token = NULL, lease_until = NULL, updated_at = :now
                WHERE id = :id AND lease_token = :token AND state = 'PENDING'
                """)
            .param("attempt", attempt)
            .param("next", ts(nextAttemptAt))
            .param("now", ts(now))
            .param("id", id)
            .param("token", leaseToken)
            .update()
        == 1;
  }

  /** Gives the lease back without changing anything else. */
  public boolean release(UUID id, UUID leaseToken) {
    return jdbc.sql(
                """
                UPDATE refunds SET lease_token = NULL, lease_until = NULL
                WHERE id = :id AND lease_token = :token
                """)
            .param("id", id)
            .param("token", leaseToken)
            .update()
        == 1;
  }

  /**
   * Hands back a lease whose provider call may still be running, as if this worker had died: the
   * token is replaced, fencing out a late write, and the lease is expired for every clock, so the
   * next poll claims the refund at once and is told it was {@code recovered}.
   */
  public boolean abandon(UUID id, UUID leaseToken) {
    return jdbc.sql(
                """
                UPDATE refunds SET lease_token = gen_random_uuid(), lease_until = '-infinity'
                WHERE id = :id AND lease_token = :token
                """)
            .param("id", id)
            .param("token", leaseToken)
            .update()
        == 1;
  }

  private Refund map(ResultSet rs) throws SQLException {
    String lines = rs.getString("lines");
    return new Refund(
        rs.getObject("id", UUID.class),
        rs.getObject("order_id", UUID.class),
        rs.getLong("amount_minor"),
        lines == null ? List.of() : read(lines),
        RefundState.valueOf(rs.getString("state")),
        rs.getString("provider_refund_id"),
        rs.getString("failure_code"),
        rs.getString("failure_message"),
        rs.getInt("attempt"),
        instant(rs, "next_attempt_at"),
        instant(rs, "created_at"),
        instant(rs, "updated_at"));
  }

  private static OffsetDateTime ts(Instant i) {
    return i == null ? null : i.atOffset(ZoneOffset.UTC);
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
    return t == null ? null : t.toInstant();
  }

  private String write(List<RefundLine> lines) {
    try {
      return json.writeValueAsString(lines);
    } catch (JacksonException e) {
      throw new IllegalStateException("can't serialise refund lines", e);
    }
  }

  private List<RefundLine> read(String value) {
    try {
      return json.readValue(value, LINES);
    } catch (JacksonException e) {
      throw new IllegalStateException("stored refund lines are not valid JSON", e);
    }
  }
}
