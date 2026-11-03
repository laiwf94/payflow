package io.github.laiwf94.payflow.order;

import io.github.laiwf94.payflow.money.Money;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Orders in Postgres. Every saga write is a compare-and-set on {@code (id, lease_token, state)}, so
 * a worker whose lease was taken over can't overwrite the new owner's progress.
 *
 * <p>Times are passed in from the caller's clock rather than taken from {@code now()} in SQL, which
 * keeps scheduling testable with a controlled clock.
 */
@Repository
public class OrderRepository {

  private static final TypeReference<List<LineItem>> ITEMS = new TypeReference<>() {};

  private final JdbcClient jdbc;
  private final ObjectMapper json;

  public OrderRepository(JdbcClient jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  /**
   * Stores a new order. Returns false, writing nothing, when the merchant already has an order with
   * the same {@code merchant_reference}.
   *
   * <p>{@code ON CONFLICT DO NOTHING} rather than catching the unique violation: an error would
   * abort the surrounding transaction, and the idempotency filter must still commit the 409 in that
   * same transaction so a retry with the same key replays it. A concurrent insert of the same
   * reference waits for the first transaction and then conflicts, or inserts if that one rolled
   * back.
   */
  public boolean insert(Order o) {
    return jdbc.sql(
                """
                INSERT INTO orders (id, merchant_id, merchant_reference, state, amount_minor,
                    currency, items, shipping_address, payment_method, step_started_at,
                    next_attempt_at, correlation_id, created_at, updated_at)
                VALUES (:id, :merchant, :reference, :state, :amount, :currency,
                    CAST(:items AS jsonb), CAST(:address AS jsonb), :method, :stepStartedAt,
                    :nextAttemptAt, :correlationId, :createdAt, :updatedAt)
                ON CONFLICT (merchant_id, merchant_reference) DO NOTHING
                """)
            .param("id", o.id())
            .param("merchant", o.merchantId())
            .param("reference", o.merchantReference())
            .param("state", o.state().name())
            .param("amount", o.amount().minorUnits())
            .param("currency", o.amount().currencyCode())
            .param("items", write(o.items()))
            .param("address", write(o.shippingAddress()))
            .param("method", o.paymentMethod())
            .param("stepStartedAt", ts(o.stepStartedAt()))
            .param("nextAttemptAt", ts(o.nextAttemptAt()))
            .param("correlationId", o.correlationId())
            .param("createdAt", ts(o.createdAt()))
            .param("updatedAt", ts(o.updatedAt()))
            .update()
        == 1;
  }

  public Optional<Order> findById(UUID id) {
    return jdbc.sql("SELECT * FROM orders WHERE id = :id")
        .param("id", id)
        .query((rs, n) -> map(rs))
        .optional();
  }

  /** Looks an order up for a merchant. Another merchant's order is indistinguishable from none. */
  public Optional<Order> findForMerchant(String merchantId, UUID id) {
    return jdbc.sql("SELECT * FROM orders WHERE id = :id AND merchant_id = :merchant")
        .param("id", id)
        .param("merchant", merchantId)
        .query((rs, n) -> map(rs))
        .optional();
  }

  /** The merchant's order with this {@code merchant_reference}, if there is one. */
  public Optional<Order> findByReference(String merchantId, String reference) {
    return jdbc.sql(
            "SELECT * FROM orders WHERE merchant_id = :merchant AND merchant_reference = :reference")
        .param("merchant", merchantId)
        .param("reference", reference)
        .query((rs, n) -> map(rs))
        .optional();
  }

  /**
   * One page of the merchant's orders that match {@code filter}, newest first by {@code
   * (created_at, id)}, starting strictly after {@code after} (or at the newest when it is null).
   *
   * <p>Keyset rather than OFFSET: the row comparison {@code (created_at, id) < (:c, :i)} is an
   * index condition on {@code (merchant_id, created_at, id)}, so a deep page costs the same as the
   * first, and an order inserted or changed while a client pages can't shift the ones after it.
   * Every order that matched when paging began and still matches is returned exactly once.
   */
  public List<Order> list(String merchantId, OrderFilter filter, OrderPosition after, int limit) {
    if (limit < 1) {
      throw new IllegalArgumentException("limit must be positive: " + limit);
    }
    StringBuilder sql = new StringBuilder("SELECT * FROM orders WHERE merchant_id = :merchant");
    Map<String, Object> params = new HashMap<>();
    params.put("merchant", merchantId);
    if (!filter.states().isEmpty()) {
      sql.append(" AND state IN (:states)");
      params.put("states", filter.states().stream().map(Enum::name).sorted().toList());
    }
    if (filter.createdFrom() != null) {
      sql.append(" AND created_at >= :createdFrom");
      params.put("createdFrom", ts(filter.createdFrom()));
    }
    if (filter.createdBefore() != null) {
      sql.append(" AND created_at < :createdBefore");
      params.put("createdBefore", ts(filter.createdBefore()));
    }
    if (filter.currency() != null) {
      sql.append(" AND currency = :currency");
      params.put("currency", filter.currency());
    }
    if (filter.merchantReference() != null) {
      sql.append(" AND merchant_reference = :reference");
      params.put("reference", filter.merchantReference());
    }
    if (after != null) {
      sql.append(" AND (created_at, id) < (:afterCreated, :afterId)");
      params.put("afterCreated", ts(after.createdAt()));
      params.put("afterId", after.id());
    }
    sql.append(" ORDER BY created_at DESC, id DESC LIMIT :limit");
    params.put("limit", limit);
    return jdbc.sql(sql.toString()).params(params).query((rs, n) -> map(rs)).list();
  }

  /**
   * Leases up to {@code limit} due orders. {@code SKIP LOCKED} lets many workers poll the same
   * table without blocking on each other's rows; the lease (not the row lock) is what protects an
   * order while its step runs, because no transaction stays open across an external call.
   */
  public List<Claim> claimDue(Instant now, Duration lease, int limit) {
    return jdbc.sql(
            """
            WITH due AS (
                SELECT id, lease_token AS expired_token
                FROM orders
                WHERE next_attempt_at <= :now
                  AND (lease_until IS NULL OR lease_until < :now)
                ORDER BY next_attempt_at
                LIMIT :limit
                FOR UPDATE SKIP LOCKED
            )
            UPDATE orders o
            SET lease_token = gen_random_uuid(), lease_until = :leaseUntil
            FROM due
            WHERE o.id = due.id
            RETURNING o.*, due.expired_token IS NOT NULL AS recovered
            """)
        .param("now", ts(now))
        .param("leaseUntil", ts(now.plus(lease)))
        .param("limit", limit)
        .query(
            (rs, n) ->
                new Claim(
                    map(rs), rs.getObject("lease_token", UUID.class), rs.getBoolean("recovered")))
        .list();
  }

  /**
   * Applies {@code change} if this worker still holds the lease and the order is still in {@code
   * expected}. Returns the updated order, or empty when the write was fenced out.
   */
  public Optional<Order> update(
      UUID id, UUID leaseToken, OrderState expected, StateChange change, Instant now) {
    if (change.state().isTerminal() != (change.nextAttemptAt() == null)) {
      throw new IllegalArgumentException(
          "terminal states have no next attempt, other states need one: " + change);
    }
    return jdbc.sql(
            """
            UPDATE orders SET
                state = :state,
                attempt = :attempt,
                step_started_at = COALESCE(CAST(:stepStartedAt AS timestamptz), step_started_at),
                next_attempt_at = CAST(:nextAttemptAt AS timestamptz),
                lease_token = CASE WHEN CAST(:keepUntil AS timestamptz) IS NULL
                                   THEN NULL ELSE lease_token END,
                lease_until = CAST(:keepUntil AS timestamptz),
                reservation_id = COALESCE(CAST(:reservationId AS text), reservation_id),
                payment_id = COALESCE(CAST(:paymentId AS text), payment_id),
                shipment_id = COALESCE(CAST(:shipmentId AS text), shipment_id),
                tracking_number = COALESCE(CAST(:trackingNumber AS text), tracking_number),
                captured_minor = COALESCE(CAST(:captured AS bigint), captured_minor),
                refunded_minor = COALESCE(CAST(:refunded AS bigint), refunded_minor),
                failure_code = COALESCE(CAST(:failureCode AS text), failure_code),
                failure_message = COALESCE(CAST(:failureMessage AS text), failure_message),
                version = version + 1,
                updated_at = :now
            WHERE id = :id AND lease_token = :token AND state = :expected
            RETURNING *
            """)
        .param("state", change.state().name())
        .param("attempt", change.attempt())
        .param("stepStartedAt", ts(change.stepStartedAt()))
        .param("nextAttemptAt", ts(change.nextAttemptAt()))
        .param("keepUntil", ts(change.keepLeaseUntil()))
        .param("reservationId", change.reservationId())
        .param("paymentId", change.paymentId())
        .param("shipmentId", change.shipmentId())
        .param("trackingNumber", change.trackingNumber())
        .param("captured", change.capturedMinor())
        .param("refunded", change.refundedMinor())
        .param("failureCode", change.failureCode())
        .param("failureMessage", change.failureMessage())
        .param("now", ts(now))
        .param("id", id)
        .param("token", leaseToken)
        .param("expected", expected.name())
        .query((rs, n) -> map(rs))
        .optional();
  }

  /**
   * Flags a cancel if the order hasn't been captured yet, and makes it due now so a pending retry
   * doesn't delay the cancellation. The worker honours the flag at its next decision point.
   */
  public Optional<Order> requestCancel(String merchantId, UUID id, Instant now) {
    return jdbc.sql(
            """
            UPDATE orders SET cancel_requested = true,
                next_attempt_at = LEAST(next_attempt_at, :now),
                version = version + 1, updated_at = :now
            WHERE id = :id AND merchant_id = :merchant
              AND state IN ('PENDING', 'INVENTORY_RESERVED', 'PAYMENT_AUTHORIZED')
            RETURNING *
            """)
        .param("now", ts(now))
        .param("id", id)
        .param("merchant", merchantId)
        .query((rs, n) -> map(rs))
        .optional();
  }

  /** Moves a completed order to {@code REFUNDING}. Completed orders are never leased. */
  public Optional<Order> requestRefund(String merchantId, UUID id, Instant now) {
    return jdbc.sql(
            """
            UPDATE orders SET state = 'REFUNDING', attempt = 0, step_started_at = :now,
                next_attempt_at = :now, version = version + 1, updated_at = :now
            WHERE id = :id AND merchant_id = :merchant AND state = 'COMPLETED'
            RETURNING *
            """)
        .param("now", ts(now))
        .param("id", id)
        .param("merchant", merchantId)
        .query((rs, n) -> map(rs))
        .optional();
  }

  /** Gives the lease back without changing anything else. */
  public boolean release(UUID id, UUID leaseToken) {
    return jdbc.sql(
                """
                UPDATE orders SET lease_token = NULL, lease_until = NULL
                WHERE id = :id AND lease_token = :token
                """)
            .param("id", id)
            .param("token", leaseToken)
            .update()
        == 1;
  }

  /**
   * Hands back a lease whose step may still be running, as if this worker had died: the token is
   * replaced, so any late write from the old step is fenced out, and the lease is expired for every
   * clock, so the next poll on any instance claims the order and is told it was {@code recovered}.
   * Returns false when the lease was no longer ours (the step finished or another worker has it).
   */
  public boolean abandon(UUID id, UUID leaseToken) {
    return jdbc.sql(
                """
                UPDATE orders SET lease_token = gen_random_uuid(), lease_until = '-infinity'
                WHERE id = :id AND lease_token = :token
                """)
            .param("id", id)
            .param("token", leaseToken)
            .update()
        == 1;
  }

  private Order map(ResultSet rs) throws SQLException {
    return new Order(
        rs.getObject("id", UUID.class),
        rs.getString("merchant_id"),
        rs.getString("merchant_reference"),
        OrderState.valueOf(rs.getString("state")),
        Money.of(rs.getLong("amount_minor"), rs.getString("currency")),
        read(rs.getString("items"), ITEMS),
        read(rs.getString("shipping_address"), new TypeReference<ShippingAddress>() {}),
        rs.getString("payment_method"),
        rs.getBoolean("cancel_requested"),
        rs.getString("reservation_id"),
        rs.getString("payment_id"),
        rs.getString("shipment_id"),
        rs.getString("tracking_number"),
        rs.getLong("captured_minor"),
        rs.getLong("refunded_minor"),
        rs.getString("failure_code"),
        rs.getString("failure_message"),
        rs.getInt("attempt"),
        instant(rs, "step_started_at"),
        instant(rs, "next_attempt_at"),
        rs.getString("correlation_id"),
        rs.getLong("version"),
        instant(rs, "created_at"),
        instant(rs, "updated_at"));
  }

  static OffsetDateTime ts(Instant i) {
    return i == null ? null : i.atOffset(ZoneOffset.UTC);
  }

  static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
    return t == null ? null : t.toInstant();
  }

  private String write(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (JacksonException e) {
      throw new IllegalStateException("can't serialise order field", e);
    }
  }

  private <T> T read(String value, TypeReference<T> type) {
    try {
      return json.readValue(value, type);
    } catch (JacksonException e) {
      throw new IllegalStateException("stored order field is not valid JSON", e);
    }
  }
}
