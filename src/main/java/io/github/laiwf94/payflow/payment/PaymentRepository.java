package io.github.laiwf94.payflow.payment;

import io.github.laiwf94.payflow.money.Money;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Payments in Postgres, one row per tender. The order's own writes shape and update them, in the
 * transaction of the order's write: {@link #split} with a new order paid several ways, {@link
 * #save} with each fenced step write, {@link #addRefunded} with a refund's. That transaction holds
 * the order's row lock, so tenders are never written by two workers at once and are read
 * consistently with it. A new order's first tender comes from the database itself, with the order's
 * insert.
 *
 * <p>Writes from a binary older than this one still go through the mirror trigger of {@code
 * V30__payments.sql}, which keeps a single tender equal to the order row; this binary's order
 * writes switch that trigger off for their transaction (see {@code V32__payments_owned.sql}).
 */
@Repository
public class PaymentRepository {

  private final JdbcClient jdbc;

  public PaymentRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Makes a new order's single tender, the one the database gave it for the whole amount, into the
   * first of {@code tenders} and adds the others, in the order's insert transaction. The first
   * keeps its row and its keys; positions run from 1 in the order given.
   *
   * @throws IllegalStateException when the order has no single pending tender to split
   */
  public void split(List<Payment> tenders) {
    Payment first = tenders.getFirst();
    int n =
        jdbc.sql(
                """
                UPDATE payments SET method = :method, amount_minor = :amount, currency = :currency
                WHERE order_id = :order AND position = 1 AND state = 'pending'
                  AND NOT EXISTS (
                      SELECT 1 FROM payments o WHERE o.order_id = :order AND o.position > 1)
                """)
            .param("method", first.method())
            .param("amount", first.amount().minorUnits())
            .param("currency", first.amount().currencyCode())
            .param("order", first.orderId())
            .update();
    if (n != 1) {
      throw new IllegalStateException("order " + first.orderId() + " has no tender to split");
    }
    for (Payment p : tenders.subList(1, tenders.size())) {
      jdbc.sql(
              """
              INSERT INTO payments (id, order_id, position, method, provider, amount_minor,
                  currency, state, provider_payment_id, authorize_key, capture_key,
                  reauthorize_key, captured_minor, refunded_minor, authorization_expires_at,
                  reauthorized_at, created_at, updated_at)
              VALUES (:id, :order, :position, :method, :provider, :amount, :currency, :state,
                  :providerPaymentId, :authorizeKey, :captureKey, :reauthorizeKey, :captured,
                  :refunded, :expires, :reauthorizedAt, :createdAt, :updatedAt)
              """)
          .param("id", p.id())
          .param("order", p.orderId())
          .param("position", p.position())
          .param("method", p.method())
          .param("provider", p.provider())
          .param("amount", p.amount().minorUnits())
          .param("currency", p.amount().currencyCode())
          .param("state", p.state().code())
          .param("providerPaymentId", p.providerPaymentId())
          .param("authorizeKey", p.authorizeKey())
          .param("captureKey", p.captureKey())
          .param("reauthorizeKey", p.reauthorizeKey())
          .param("captured", p.capturedMinor())
          .param("refunded", p.refundedMinor())
          .param("expires", ts(p.authorizationExpiresAt()))
          .param("reauthorizedAt", ts(p.reauthorizedAt()))
          .param("createdAt", ts(p.createdAt()))
          .param("updatedAt", ts(p.updatedAt()))
          .update();
    }
  }

  /**
   * Writes what a step learned about each of {@code tenders} (state, provider id, money, hold
   * times), in the transaction of the order's fenced write, which is what makes this one safe.
   * Every tender must belong to {@code orderId}.
   *
   * @throws IllegalStateException when a tender is not the order's, so the transaction must not
   *     commit
   */
  public void save(UUID orderId, List<Payment> tenders, Instant now) {
    for (Payment p : tenders) {
      int n =
          jdbc.sql(
                  """
                  UPDATE payments SET state = :state, provider_payment_id = :providerPaymentId,
                      captured_minor = :captured, refunded_minor = :refunded,
                      authorization_expires_at = :expires, reauthorized_at = :reauthorizedAt,
                      updated_at = :now
                  WHERE id = :id AND order_id = :order
                  """)
              .param("state", p.state().code())
              .param("providerPaymentId", p.providerPaymentId())
              .param("captured", p.capturedMinor())
              .param("refunded", p.refundedMinor())
              .param("expires", ts(p.authorizationExpiresAt()))
              .param("reauthorizedAt", ts(p.reauthorizedAt()))
              .param("now", ts(now))
              .param("id", p.id())
              .param("order", orderId)
              .update();
      if (n != 1) {
        throw new IllegalStateException("payment " + p.id() + " is not a tender of " + orderId);
      }
    }
  }

  /**
   * Adds {@code amount}, a tender's share of a settled refund, to tender {@code paymentId} of
   * {@code orderId}, in the refund's transaction. Returns false when the order has no such tender.
   * The tender's checks refuse more refunded than it captured.
   */
  public boolean addRefunded(UUID orderId, UUID paymentId, long amount, Instant now) {
    return jdbc.sql(
                """
                UPDATE payments SET refunded_minor = refunded_minor + :amount,
                    state = CASE WHEN refunded_minor + :amount = captured_minor
                                 THEN 'refunded' ELSE 'partially_refunded' END,
                    updated_at = :now
                WHERE order_id = :order AND id = :payment
                """)
            .param("amount", amount)
            .param("now", ts(now))
            .param("order", orderId)
            .param("payment", paymentId)
            .update()
        == 1;
  }

  /**
   * Settles every tender of {@code orderId} still {@code pending} as {@code canceled}, in the
   * transaction that ends the order unpaid. Only a tender the saga never got to is still pending
   * then: one it tried has been recorded, or reversed, before the order could end.
   */
  public void cancelPending(UUID orderId, Instant now) {
    jdbc.sql(
            """
            UPDATE payments SET state = 'canceled', updated_at = :now
            WHERE order_id = :order AND state = 'pending'
            """)
        .param("now", ts(now))
        .param("order", orderId)
        .update();
  }

  /** The order's tenders by position. Empty for an order that doesn't exist. */
  public List<Payment> forOrder(UUID orderId) {
    return jdbc.sql("SELECT * FROM payments WHERE order_id = :order ORDER BY position")
        .param("order", orderId)
        .query((rs, n) -> map(rs))
        .list();
  }

  /**
   * The tenders of each of {@code orderIds} by position, in one query. An order with none (or that
   * doesn't exist) is absent from the map.
   */
  public Map<UUID, List<Payment>> forOrders(Collection<UUID> orderIds) {
    if (orderIds.isEmpty()) {
      return Map.of();
    }
    Map<UUID, List<Payment>> byOrder = new HashMap<>();
    jdbc.sql("SELECT * FROM payments WHERE order_id IN (:orders) ORDER BY order_id, position")
        .param("orders", orderIds)
        .query((rs, n) -> map(rs))
        .list()
        .forEach(p -> byOrder.computeIfAbsent(p.orderId(), k -> new ArrayList<>()).add(p));
    byOrder.replaceAll((k, v) -> List.copyOf(v));
    return byOrder;
  }

  private static Payment map(ResultSet rs) throws SQLException {
    return new Payment(
        rs.getObject("id", UUID.class),
        rs.getObject("order_id", UUID.class),
        rs.getInt("position"),
        rs.getString("method"),
        rs.getString("provider"),
        Money.of(rs.getLong("amount_minor"), rs.getString("currency")),
        PaymentState.fromCode(rs.getString("state")),
        rs.getString("provider_payment_id"),
        rs.getString("authorize_key"),
        rs.getString("capture_key"),
        rs.getString("reauthorize_key"),
        rs.getLong("captured_minor"),
        rs.getLong("refunded_minor"),
        instant(rs, "authorization_expires_at"),
        instant(rs, "reauthorized_at"),
        instant(rs, "created_at"),
        instant(rs, "updated_at"));
  }

  private static OffsetDateTime ts(Instant t) {
    return t == null ? null : t.atOffset(ZoneOffset.UTC);
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
    return t == null ? null : t.toInstant();
  }
}
