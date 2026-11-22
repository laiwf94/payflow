package io.github.laiwf94.payflow.payment;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.laiwf94.payflow.support.Containers;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * V30 and V31 on a database an older release filled with orders in every state: each order ends up
 * with exactly one tender that says what the order row says, including an order the saga moved on
 * between the two migrations, and running the backfill again adds nothing. Runs on a database of
 * its own, migrated step by step.
 */
class PaymentsBackfillMigrationTest {

  private final String database = "payments_" + UUID.randomUUID().toString().replace("-", "");
  private String url;

  @BeforeEach
  void createDatabase() throws SQLException {
    try (Connection c = admin();
        Statement s = c.createStatement()) {
      s.execute("CREATE DATABASE " + database);
    }
    String base = Containers.POSTGRES.getJdbcUrl();
    url = base.substring(0, base.lastIndexOf('/') + 1) + database;
  }

  @AfterEach
  void dropDatabase() throws SQLException {
    try (Connection c = admin();
        Statement s = c.createStatement()) {
      s.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
    }
  }

  private static Connection admin() throws SQLException {
    return DriverManager.getConnection(
        Containers.POSTGRES.getJdbcUrl(),
        Containers.POSTGRES.getUsername(),
        Containers.POSTGRES.getPassword());
  }

  private Connection connect() throws SQLException {
    return DriverManager.getConnection(
        url, Containers.POSTGRES.getUsername(), Containers.POSTGRES.getPassword());
  }

  private Flyway flyway(String target) {
    return Flyway.configure()
        .dataSource(url, Containers.POSTGRES.getUsername(), Containers.POSTGRES.getPassword())
        .locations("classpath:db/migration")
        .configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
        .target(target)
        .load();
  }

  private void exec(String sql) throws SQLException {
    try (Connection c = connect();
        Statement s = c.createStatement()) {
      s.execute(sql);
    }
  }

  private List<String> rows(String sql) throws SQLException {
    List<String> out = new ArrayList<>();
    try (Connection c = connect();
        Statement s = c.createStatement();
        var rs = s.executeQuery(sql)) {
      while (rs.next()) {
        out.add(rs.getString(1));
      }
    }
    return out;
  }

  private String one(String sql) throws SQLException {
    return rows(sql).getFirst();
  }

  /**
   * One order per row: its state, payment id, money, failure code and hold times as an older
   * release would have left them, and the tender state the backfill must give it.
   */
  private static final String[][] ORDERS = {
    {"PENDING", null, "0", "0", null, "pending"},
    {"INVENTORY_RESERVED", null, "0", "0", null, "pending"},
    {"PAYMENT_AUTHORIZED", "pay_a", "0", "0", null, "authorized"},
    {"PAYMENT_CAPTURED", "pay_b", "2500", "0", null, "captured"},
    {"COMPLETED", "pay_c", "2500", "0", null, "captured"},
    {"PARTIALLY_REFUNDED", "pay_d", "2500", "700", null, "partially_refunded"},
    {"REFUNDED", "pay_e", "2500", "2500", null, "refunded"},
    {"REVERSING_PAYMENT", "pay_f", "0", "0", null, "authorized"},
    {"FAILED", null, "0", "0", "payment_declined", "declined"},
    {"FAILED", "pay_g", "0", "0", "authorization_expired", "expired"},
    {"FAILED", "pay_h", "0", "0", "shipping_failed", "voided"},
    {"FAILED", "pay_i", "2500", "2500", "shipping_failed", "refunded"},
    {"CANCELLED", null, "0", "0", null, "canceled"},
  };

  private static String previous(String state) {
    return switch (state) {
      case "PENDING" -> "NULL";
      case "INVENTORY_RESERVED" -> "'PENDING'";
      case "PAYMENT_AUTHORIZED" -> "'INVENTORY_RESERVED'";
      case "PAYMENT_CAPTURED", "REVERSING_PAYMENT" -> "'PAYMENT_AUTHORIZED'";
      case "COMPLETED" -> "'PAYMENT_CAPTURED'";
      case "PARTIALLY_REFUNDED" -> "'COMPLETED'";
      case "REFUNDED" -> "'PARTIALLY_REFUNDED'";
      case "FAILED", "CANCELLED" -> "'RELEASING_INVENTORY'";
      default -> throw new IllegalArgumentException(state);
    };
  }

  @Test
  void everyOrderGetsTheTenderItsRowDescribesAndTheBackfillCanRunAgain()
      throws SQLException, IOException {
    flyway("29").migrate();
    List<UUID> ids = new ArrayList<>();
    for (String[] o : ORDERS) {
      UUID id = UUID.randomUUID();
      ids.add(id);
      boolean terminal =
          List.of("COMPLETED", "PARTIALLY_REFUNDED", "REFUNDED", "FAILED", "CANCELLED")
              .contains(o[0]);
      // Inserted straight into the state with a legal previous_state. The trigger that records
      // previous_state would reset it to NULL on an insert, so triggers are off for the load.
      exec(
          """
          SET session_replication_role = replica;
          INSERT INTO orders (id, merchant_id, state, previous_state, amount_minor, currency, items,
              shipping_address, payment_method, payment_id, captured_minor, refunded_minor,
              failure_code, failure_message, authorization_expires_at, step_started_at,
              next_attempt_at, correlation_id, created_at, updated_at)
          VALUES ('%s', 'acme', '%s', %s, 2500, 'EUR', '[]', '{}', 'pm_card_ok', %s, %s, %s, %s, %s,
              %s, now(), %s, 'corr', '2026-11-01T00:00:00Z', '2026-11-02T00:00:00Z');
          """
              .formatted(
                  id,
                  o[0],
                  previous(o[0]),
                  o[1] == null ? "NULL" : "'" + o[1] + "'",
                  o[2],
                  o[3],
                  o[4] == null ? "NULL" : "'" + o[4] + "'",
                  o[4] == null ? "NULL" : "'why'",
                  o[1] == null ? "NULL" : "'2026-11-09T00:00:00Z'",
                  terminal ? "NULL" : "now()"));
    }
    // More than one batch of plain pending orders.
    exec(
        """
        INSERT INTO orders (id, merchant_id, state, amount_minor, currency, items,
            shipping_address, payment_method, step_started_at, next_attempt_at, correlation_id,
            created_at, updated_at)
        SELECT gen_random_uuid(), 'bulk', 'PENDING', 100 + n, 'USD', '[]', '{}', 'pm_card_ok',
            now(), now(), 'corr', now(), now()
        FROM generate_series(1, 4500) n
        """);

    flyway("30").migrate();
    assertThat(one("SELECT count(*)::text FROM payments"))
        .as("V30 only adds the table")
        .isEqualTo("0");
    // The saga authorizes the first order between the two migrations: the trigger writes its
    // tender, and the backfill must leave that row as it is.
    exec(
        """
        UPDATE orders SET state = 'INVENTORY_RESERVED', updated_at = '2026-11-03T00:00:00Z'
        WHERE id = '%s'
        """
            .formatted(ids.getFirst()));
    exec(
        """
        UPDATE orders SET state = 'PAYMENT_AUTHORIZED', payment_id = 'pay_late',
            updated_at = '2026-11-04T00:00:00Z'
        WHERE id = '%s'
        """
            .formatted(ids.getFirst()));
    assertThat(one("SELECT count(*)::text FROM payments")).isEqualTo("1");

    flyway("latest").migrate();
    assertThat(one("SELECT count(*)::text FROM payments"))
        .isEqualTo(String.valueOf(ORDERS.length + 4500));
    assertThat(
            one(
                """
                SELECT count(*)::text FROM orders o
                WHERE (SELECT count(*) FROM payments p WHERE p.order_id = o.id) <> 1
                """))
        .as("orders without exactly one tender")
        .isEqualTo("0");
    assertThat(
            one(
                """
                SELECT count(*)::text FROM orders o JOIN payments p ON p.order_id = o.id
                WHERE p.position <> 1 OR p.method <> o.payment_method OR p.provider <> 'primary'
                   OR p.amount_minor <> o.amount_minor OR p.currency <> o.currency
                   OR p.provider_payment_id IS DISTINCT FROM o.payment_id
                   OR p.captured_minor <> o.captured_minor
                   OR p.refunded_minor <> o.refunded_minor
                   OR p.authorization_expires_at IS DISTINCT FROM o.authorization_expires_at
                   OR p.authorize_key <> 'authorize-' || o.id
                   OR p.capture_key <> 'capture-' || o.id
                   OR p.reauthorize_key <> 'reauthorize-' || o.id
                   OR p.created_at <> o.created_at OR p.updated_at <> o.updated_at
                """))
        .as("tenders that disagree with their order")
        .isEqualTo("0");

    List<String> expected = new ArrayList<>();
    expected.add("authorized pay_late");
    for (int i = 1; i < ORDERS.length; i++) {
      expected.add(ORDERS[i][5] + " " + (ORDERS[i][1] == null ? "-" : ORDERS[i][1]));
    }
    List<String> actual = new ArrayList<>();
    for (UUID id : ids) {
      actual.add(
          one(
              "SELECT state || ' ' || coalesce(provider_payment_id, '-') FROM payments"
                  + " WHERE order_id = '%s'".formatted(id)));
    }
    assertThat(actual).containsExactlyElementsOf(expected);
    assertThat(
            one(
                "SELECT count(*)::text FROM payments p JOIN orders o ON o.id = p.order_id"
                    + " WHERE o.merchant_id = 'bulk' AND p.state = 'pending'"))
        .isEqualTo("4500");

    String before = String.join(",", rows("SELECT id::text FROM payments ORDER BY id"));
    String backfill;
    try (InputStream in =
        getClass().getResourceAsStream("/db/migration/V31__backfill_payments.sql")) {
      backfill = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    exec(backfill);
    assertThat(String.join(",", rows("SELECT id::text FROM payments ORDER BY id")))
        .as("a second run adds and changes nothing")
        .isEqualTo(before);

    // New orders get their tender from the trigger alone.
    exec(
        """
        INSERT INTO orders (id, merchant_id, state, amount_minor, currency, items,
            shipping_address, payment_method, step_started_at, next_attempt_at, correlation_id,
            created_at, updated_at)
        VALUES (gen_random_uuid(), 'acme', 'PENDING', 900, 'GBP', '[]', '{}', 'pm_card_ok',
            now(), now(), 'corr', now(), now())
        """);
    assertThat(one("SELECT count(*)::text FROM payments WHERE currency = 'GBP'")).isEqualTo("1");
  }
}
