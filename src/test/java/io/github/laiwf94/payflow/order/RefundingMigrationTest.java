package io.github.laiwf94.payflow.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.laiwf94.payflow.support.Containers;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The contract migration that drops {@code REFUNDING} (V14) on a database an older release left
 * with an order mid full-refund: it must refuse, change nothing, and go through once that refund
 * has finished. Runs on a database of its own, migrated step by step.
 */
class RefundingMigrationTest {

  private final String database = "migrate_" + UUID.randomUUID().toString().replace("-", "");
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

  private String one(String sql) throws SQLException {
    try (Connection c = connect();
        Statement s = c.createStatement();
        var rs = s.executeQuery(sql)) {
      rs.next();
      return rs.getString(1);
    }
  }

  @Test
  void refusesWhileAnOrderIsStillRefundingAndGoesThroughOnceItIsDone() throws SQLException {
    flyway("13").migrate();
    UUID id = UUID.randomUUID();
    exec(
        """
        INSERT INTO orders (id, merchant_id, state, amount_minor, currency, items,
            shipping_address, payment_method, step_started_at, next_attempt_at, correlation_id,
            created_at, updated_at)
        VALUES ('%s', 'acme', 'PENDING', 2500, 'EUR', '[]', '{}', 'pm_card_ok', now(), now(),
            'corr', now(), now())
        """
            .formatted(id));
    for (String state :
        new String[] {
          "INVENTORY_RESERVED", "PAYMENT_AUTHORIZED", "PAYMENT_CAPTURED", "COMPLETED", "REFUNDING"
        }) {
      boolean done = state.equals("COMPLETED");
      boolean captured = done || state.equals("REFUNDING");
      exec(
          "UPDATE orders SET state = '%s', next_attempt_at = %s, captured_minor = %d WHERE id = '%s'"
              .formatted(state, done ? "NULL" : "now()", captured ? 2500 : 0, id));
    }

    assertThatThrownBy(() -> flyway("latest").migrate())
        .isInstanceOf(FlywayException.class)
        .hasMessageContaining("orders_drop_refunding")
        .hasMessageContaining("1 orders are still REFUNDING");
    assertThat(one("SELECT max(version::int)::text FROM flyway_schema_history WHERE success"))
        .as("V14 left nothing behind")
        .isEqualTo("13");
    // The old step is still allowed to finish its refund.
    exec(
        "UPDATE orders SET state = 'REFUNDED', next_attempt_at = NULL, refunded_minor = 2500"
            + " WHERE id = '%s'".formatted(id));

    flyway("latest").migrate();
    assertThat(one("SELECT max(version::int)::text FROM flyway_schema_history WHERE success"))
        .isEqualTo("15");
    assertThat(
            one(
                "SELECT state || ' from ' || previous_state FROM orders WHERE id = '%s'"
                    .formatted(id)))
        .as("an order the old step refunded stays valid")
        .isEqualTo("REFUNDED from REFUNDING");
    assertThat(
            one(
                "SELECT count(*)::text FROM pg_constraint WHERE conrelid = 'orders'::regclass"
                    + " AND NOT convalidated"))
        .isEqualTo("0");
    UUID other = UUID.randomUUID();
    exec(
        """
        INSERT INTO orders (id, merchant_id, state, amount_minor, currency, items,
            shipping_address, payment_method, step_started_at, next_attempt_at, correlation_id,
            created_at, updated_at)
        VALUES ('%s', 'acme', 'PENDING', 2500, 'EUR', '[]', '{}', 'pm_card_ok', now(), now(),
            'corr', now(), now())
        """
            .formatted(other));
    assertThatThrownBy(
            () -> exec("UPDATE orders SET state = 'REFUNDING' WHERE id = '%s'".formatted(other)))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("orders_known_state");
  }
}
