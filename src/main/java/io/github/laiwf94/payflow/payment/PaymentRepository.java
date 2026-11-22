package io.github.laiwf94.payflow.payment;

import io.github.laiwf94.payflow.money.Money;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Payments in Postgres, one row per tender. Until the payment steps write these rows themselves,
 * the database keeps each order's single tender equal to the saga's copy on the order row, in the
 * same statement as every write of it (see {@code V30__payments.sql}), so reading both in one
 * transaction always agrees.
 */
@Repository
public class PaymentRepository {

  private final JdbcClient jdbc;

  public PaymentRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
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

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
    return t == null ? null : t.toInstant();
  }
}
