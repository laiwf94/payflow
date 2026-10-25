package io.github.laiwf94.payflow.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.laiwf94.payflow.support.Db;
import io.github.laiwf94.payflow.support.TestInfrastructure;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Drives orders through raw SQL, bypassing the repository, to show the database itself refuses
 * illegal transitions and broken money invariants.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestInfrastructure.class)
class OrderSchemaTest {

  @Autowired JdbcClient jdbc;
  @Autowired OrderRepository repo;

  @BeforeEach
  void clean() {
    Db.truncateAll(jdbc);
  }

  @Test
  void databaseAcceptsExactlyTheTransitionsTheCodeAllows() {
    Map<OrderState, List<OrderState>> paths = pathsFromPending();
    int accepted = 0;
    for (OrderState from : OrderState.values()) {
      for (OrderState to : OrderState.values()) {
        if (from == to) {
          continue;
        }
        UUID id = insertPending();
        for (OrderState step : paths.get(from)) {
          move(id, step);
        }
        if (from.canTransitionTo(to)) {
          move(id, to);
          assertThat(state(id)).isEqualTo(to.name());
          accepted++;
        } else {
          assertThatThrownBy(() -> move(id, to))
              .as("%s -> %s", from, to)
              .isInstanceOf(DataIntegrityViolationException.class)
              .hasMessageContaining("orders_legal_transition");
        }
      }
    }
    assertThat(accepted).isEqualTo(15);
  }

  @Test
  void newOrdersMustStartPendingEvenWithAForgedPreviousState() {
    assertThatThrownBy(
            () ->
                jdbc.sql(insertSql("INVENTORY_RESERVED", "now()", 2500))
                    .param("id", UUID.randomUUID())
                    .update())
        .hasMessageContaining("orders_legal_transition");
    UUID id = insertPending();
    jdbc.sql("UPDATE orders SET previous_state = 'RELEASING_INVENTORY' WHERE id = :id")
        .param("id", id)
        .update();
    assertThat(previous(id)).isNull();
    assertThatThrownBy(() -> move(id, OrderState.FAILED))
        .hasMessageContaining("orders_legal_transition");
  }

  @Test
  void updatesThatKeepTheStateKeepThePreviousState() {
    UUID id = insertPending();
    move(id, OrderState.INVENTORY_RESERVED);
    jdbc.sql("UPDATE orders SET attempt = 3 WHERE id = :id").param("id", id).update();
    assertThat(previous(id)).isEqualTo("PENDING");
  }

  @Test
  void completedOrdersMustBeFullyCaptured() {
    UUID id = insertPending();
    move(id, OrderState.INVENTORY_RESERVED);
    move(id, OrderState.PAYMENT_AUTHORIZED);
    move(id, OrderState.PAYMENT_CAPTURED);
    assertThatThrownBy(
            () ->
                jdbc.sql(
                        """
                        UPDATE orders SET state = 'COMPLETED', next_attempt_at = NULL,
                          captured_minor = amount_minor - 1 WHERE id = :id
                        """)
                    .param("id", id)
                    .update())
        .hasMessageContaining("orders_completed_fully_captured");
  }

  @Test
  void failedOrdersMustNotKeepMoney() {
    UUID id = insertPending();
    move(id, OrderState.INVENTORY_RESERVED);
    move(id, OrderState.REVERSING_PAYMENT);
    jdbc.sql("UPDATE orders SET captured_minor = 2500 WHERE id = :id").param("id", id).update();
    move(id, OrderState.RELEASING_INVENTORY);
    assertThatThrownBy(
            () ->
                jdbc.sql(
                        """
                        UPDATE orders SET state = 'FAILED', next_attempt_at = NULL,
                          captured_minor = 2500, refunded_minor = 2000 WHERE id = :id
                        """)
                    .param("id", id)
                    .update())
        .hasMessageContaining("orders_failed_net_zero");
  }

  @Test
  void refundsCannotExceedCapturesAndCapturesCannotExceedTheAmount() {
    UUID id = insertPending();
    assertThatThrownBy(
            () ->
                jdbc.sql("UPDATE orders SET captured_minor = 2501 WHERE id = :id")
                    .param("id", id)
                    .update())
        .hasMessageContaining("orders_money_bounds");
    assertThatThrownBy(
            () ->
                jdbc.sql("UPDATE orders SET refunded_minor = 1 WHERE id = :id")
                    .param("id", id)
                    .update())
        .hasMessageContaining("orders_money_bounds");
  }

  @Test
  void terminalOrdersAreNeitherScheduledNorLeased() {
    UUID id = insertPending();
    assertThatThrownBy(
            () ->
                jdbc.sql("UPDATE orders SET next_attempt_at = NULL WHERE id = :id")
                    .param("id", id)
                    .update())
        .hasMessageContaining("orders_terminal_unscheduled");
    assertThatThrownBy(
            () ->
                jdbc.sql("UPDATE orders SET lease_token = gen_random_uuid() WHERE id = :id")
                    .param("id", id)
                    .update())
        .hasMessageContaining("orders_lease_pair");
  }

  @Test
  void rejectsMalformedColumns() {
    assertThatThrownBy(
            () ->
                jdbc.sql(insertSql("PENDING", "now()", 0)).param("id", UUID.randomUUID()).update())
        .hasMessageContaining("orders_amount_range");
    assertThatThrownBy(
            () ->
                jdbc.sql(insertSql("PENDING", "now()", 100).replace("'acme'", "'Acme Corp'"))
                    .param("id", UUID.randomUUID())
                    .update())
        .hasMessageContaining("orders_merchant_format");
    assertThatThrownBy(
            () ->
                jdbc.sql(insertSql("PENDING", "now()", 100).replace("'EUR'", "'eur'"))
                    .param("id", UUID.randomUUID())
                    .update())
        .hasMessageContaining("orders_currency_format");
    assertThatThrownBy(
            () ->
                jdbc.sql(insertSql("SHIPPED", "now()", 100))
                    .param("id", UUID.randomUUID())
                    .update())
        .hasMessageContaining("orders_known_state");
  }

  private UUID insertPending() {
    UUID id = UUID.randomUUID();
    repo.insert(
        new Order(
            id,
            "acme",
            OrderState.PENDING,
            io.github.laiwf94.payflow.money.Money.of(2500, "EUR"),
            Orders.pending("acme", Instant.now()).items(),
            Orders.ADDRESS,
            "pm_card_ok",
            false,
            null,
            null,
            null,
            null,
            0,
            0,
            null,
            null,
            0,
            Instant.now(),
            Instant.now(),
            "corr",
            0,
            Instant.now(),
            Instant.now()));
    return id;
  }

  /** Moves the row with whatever money columns the target state requires. */
  private void move(UUID id, OrderState to) {
    boolean captured =
        to == OrderState.COMPLETED || to == OrderState.REFUNDING || to == OrderState.REFUNDED;
    jdbc.sql(
            """
            UPDATE orders SET state = :state,
              next_attempt_at = CASE WHEN :terminal THEN NULL ELSE now() END,
              captured_minor = CASE WHEN :captured THEN amount_minor ELSE 0 END,
              refunded_minor = CASE WHEN :refunded THEN amount_minor ELSE 0 END
            WHERE id = :id
            """)
        .param("state", to.name())
        .param("terminal", to.isTerminal())
        .param("captured", captured)
        .param("refunded", to == OrderState.REFUNDED)
        .param("id", id)
        .update();
  }

  private String state(UUID id) {
    return jdbc.sql("SELECT state FROM orders WHERE id = :id")
        .param("id", id)
        .query(String.class)
        .single();
  }

  private String previous(UUID id) {
    return jdbc.sql("SELECT previous_state FROM orders WHERE id = :id")
        .param("id", id)
        .query(String.class)
        .optional()
        .orElse(null);
  }

  private static String insertSql(String state, String nextAttempt, long amount) {
    return """
        INSERT INTO orders (id, merchant_id, state, previous_state, amount_minor, currency, items,
          shipping_address, payment_method, step_started_at, next_attempt_at, correlation_id,
          created_at, updated_at, captured_minor)
        VALUES (:id, 'acme', '%s', 'PAYMENT_CAPTURED', %d, 'EUR', '[]', '{}', 'pm_card_ok', now(),
          %s, 'corr', now(), now(), 0)
        """
        .formatted(state, amount, nextAttempt);
  }

  private static Map<OrderState, List<OrderState>> pathsFromPending() {
    Map<OrderState, List<OrderState>> paths = new EnumMap<>(OrderState.class);
    paths.put(OrderState.PENDING, List.of());
    ArrayDeque<OrderState> queue = new ArrayDeque<>(List.of(OrderState.PENDING));
    while (!queue.isEmpty()) {
      OrderState s = queue.poll();
      for (OrderState n : s.next()) {
        if (!paths.containsKey(n)) {
          List<OrderState> p = new ArrayList<>(paths.get(s));
          p.add(n);
          paths.put(n, p);
          queue.add(n);
        }
      }
    }
    return paths;
  }
}
