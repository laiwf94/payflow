package io.github.laiwf94.payflow.order;

import io.github.laiwf94.payflow.money.Money;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
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

  public void insert(Order o) {
    jdbc.sql(
            """
            INSERT INTO orders (id, merchant_id, state, amount_minor, currency, items,
                shipping_address, payment_method, step_started_at, next_attempt_at,
                correlation_id, created_at, updated_at)
            VALUES (:id, :merchant, :state, :amount, :currency, CAST(:items AS jsonb),
                CAST(:address AS jsonb), :method, :stepStartedAt, :nextAttemptAt,
                :correlationId, :createdAt, :updatedAt)
            """)
        .param("id", o.id())
        .param("merchant", o.merchantId())
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
        .update();
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

  private Order map(ResultSet rs) throws SQLException {
    return new Order(
        rs.getObject("id", UUID.class),
        rs.getString("merchant_id"),
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
