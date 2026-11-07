package io.github.laiwf94.payflow.refund;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.laiwf94.payflow.order.Order;
import io.github.laiwf94.payflow.order.OrderRepository;
import io.github.laiwf94.payflow.order.Orders;
import io.github.laiwf94.payflow.support.Db;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Writes refunds through raw SQL, bypassing any service, to show the database itself refuses
 * refunds that add up to more than the order captured, or that return more of a sku than it bought,
 * including when transactions race each other.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RefundSchemaTest {

  // Orders.pending: 2500 EUR, sku-1 x2 and sku-2 x1.
  private static final long CAPTURED = 2500;

  @Autowired JdbcClient jdbc;
  @Autowired OrderRepository repo;
  @Autowired PlatformTransactionManager txManager;

  @BeforeEach
  void clean() {
    Db.truncateAll(jdbc);
  }

  @Test
  void refundsMayAddUpToTheCaptureButNotOneUnitMore() {
    UUID order = completedOrder();
    insert(order, 1000, null, "SUCCEEDED");
    insert(order, 1499, null, "PENDING");
    assertThatThrownBy(() -> insert(order, 2, null, "PENDING"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("refunds_within_captured");
    insert(order, 1, null, "PENDING");
    assertThat(active(order)).isEqualTo(CAPTURED);
    assertThatThrownBy(() -> insert(order, 1, null, "PENDING"))
        .hasMessageContaining("refunds_within_captured");
  }

  @Test
  void failedRefundsGiveTheirShareBack() {
    UUID order = completedOrder();
    UUID first = insert(order, CAPTURED, null, "PENDING");
    assertThatThrownBy(() -> insert(order, 1, null, "PENDING"))
        .hasMessageContaining("refunds_within_captured");
    setState(first, "FAILED", null);
    insert(order, CAPTURED, null, "PENDING");
    // A failed refund may itself be over the bound: it promises nothing.
    insert(order, CAPTURED, null, "FAILED");
    assertThat(active(order)).isEqualTo(CAPTURED);
  }

  @Test
  void theBoundIsCheckedAtCommitSoATransactionMayPassThroughOverIt() {
    UUID order = completedOrder();
    UUID first = insert(order, 2000, null, "PENDING");
    tx(TransactionDefinition.ISOLATION_DEFAULT)
        .executeWithoutResult(
            s -> {
              insert(order, 2000, null, "PENDING");
              setState(first, "FAILED", null);
            });
    assertThat(active(order)).isEqualTo(2000);
    assertThatThrownBy(
            () ->
                tx(TransactionDefinition.ISOLATION_DEFAULT)
                    .executeWithoutResult(
                        s -> {
                          insert(order, 501, null, "PENDING");
                          insert(order, 1, null, "FAILED");
                        }))
        .hasMessageContaining("refunds_within_captured");
    assertThat(count(order)).isEqualTo(2);
  }

  @Test
  void concurrentRefundsNeverExceedTheCaptureInReadCommitted() throws Exception {
    for (int round = 0; round < 10; round++) {
      UUID order = completedOrder();
      // Six refunds of 600 race for 2500: exactly four fit.
      List<Boolean> committed = race(order, 6, 600, TransactionDefinition.ISOLATION_READ_COMMITTED);
      assertThat(committed.stream().filter(b -> b).count()).as("round %d", round).isEqualTo(4);
      assertThat(active(order)).isEqualTo(2400);
    }
  }

  @Test
  void concurrentRefundsNeverExceedTheCaptureInSerializable() throws Exception {
    for (int round = 0; round < 5; round++) {
      UUID order = completedOrder();
      List<Boolean> committed = race(order, 4, 1000, TransactionDefinition.ISOLATION_SERIALIZABLE);
      // Serializable may abort a transaction that would have fit, but never lets one too many in.
      assertThat(committed.stream().filter(b -> b).count()).isBetween(1L, 2L);
      assertThat(active(order)).isLessThanOrEqualTo(CAPTURED);
    }
  }

  @Test
  void repeatableReadIsRefusedBecauseItsSnapshotWouldMissARacingRefund() {
    UUID order = completedOrder();
    assertThatThrownBy(
            () ->
                tx(TransactionDefinition.ISOLATION_REPEATABLE_READ)
                    .executeWithoutResult(s -> insert(order, 100, null, "PENDING")))
        .hasMessageContaining("refunds_isolation");
    assertThat(count(order)).isZero();
  }

  @Test
  void lineRefundsReturnAtMostWhatWasBought() {
    UUID order = completedOrder();
    insert(order, 1000, "[{\"sku\": \"sku-1\", \"quantity\": 1}]", "PENDING");
    insert(
        order,
        1500,
        "[{\"sku\": \"sku-1\", \"quantity\": 1}, {\"sku\": \"sku-2\", \"quantity\": 1}]",
        "SUCCEEDED");
    assertThatThrownBy(() -> insert(order, 1, "[{\"sku\": \"sku-2\", \"quantity\": 1}]", "PENDING"))
        .hasMessageContaining("refunds_within");

    UUID other = completedOrder();
    assertThatThrownBy(
            () -> insert(other, 100, "[{\"sku\": \"sku-1\", \"quantity\": 3}]", "PENDING"))
        .hasMessageContaining("refunds_within_lines")
        .hasMessageContaining("sku-1");
    assertThatThrownBy(
            () -> insert(other, 100, "[{\"sku\": \"sku-9\", \"quantity\": 1}]", "PENDING"))
        .hasMessageContaining("refunds_within_lines")
        .hasMessageContaining("sku-9");
    // Two lines of one sku in one refund count together.
    assertThatThrownBy(
            () ->
                insert(
                    other,
                    100,
                    "[{\"sku\": \"sku-2\", \"quantity\": 1}, {\"sku\": \"sku-2\", \"quantity\": 1}]",
                    "PENDING"))
        .hasMessageContaining("refunds_within_lines");
    UUID failed = insert(other, 100, "[{\"sku\": \"sku-2\", \"quantity\": 1}]", "PENDING");
    setState(failed, "FAILED", null);
    insert(other, 100, "[{\"sku\": \"sku-2\", \"quantity\": 1}]", "PENDING");
  }

  @Test
  void onlyCompletedOrdersTakeRefundsAndAnOrderWithRefundsStaysRefundable() {
    UUID pending = pendingOrder();
    assertThatThrownBy(() -> insert(pending, 100, null, "PENDING"))
        .hasMessageContaining("refunds_order_completed");

    UUID order = completedOrder();
    insert(order, 1000, null, "SUCCEEDED");
    jdbc.sql(
            """
            UPDATE orders SET state = 'PARTIALLY_REFUNDED', refunded_minor = 1000 WHERE id = :id
            """)
        .param("id", order)
        .update();
    insert(order, 1500, null, "SUCCEEDED");
    jdbc.sql("UPDATE orders SET state = 'REFUNDED', refunded_minor = 2500 WHERE id = :id")
        .param("id", order)
        .update();
    assertThat(
            jdbc.sql("SELECT state FROM orders WHERE id = :id")
                .param("id", order)
                .query(String.class)
                .single())
        .isEqualTo("REFUNDED");
  }

  @Test
  void aCaptureCannotShrinkBelowItsRefunds() {
    UUID order = completedOrder();
    insert(order, 2000, null, "PENDING");
    // Leaving COMPLETED is the only way the capture can shrink, and both checks see it.
    assertThatThrownBy(
            () ->
                jdbc.sql(
                        """
                        UPDATE orders SET state = 'PARTIALLY_REFUNDED', refunded_minor = 1,
                          captured_minor = 1999, amount_minor = 1999
                        WHERE id = :id
                        """)
                    .param("id", order)
                    .update())
        .hasMessageContaining("refunds_within_captured");
  }

  @Test
  void refundsKeepTheirAmountAndOnlyLeavePending() {
    UUID order = completedOrder();
    UUID id = insert(order, 100, "[{\"sku\": \"sku-1\", \"quantity\": 1}]", "PENDING");
    for (String change :
        List.of(
            "amount_minor = 99",
            "lines = '[{\"sku\": \"sku-2\", \"quantity\": 1}]'",
            "lines = NULL",
            "order_id = gen_random_uuid()",
            "created_at = created_at - interval '1 day'")) {
      assertThatThrownBy(
              () ->
                  jdbc.sql("UPDATE refunds SET " + change + " WHERE id = :id")
                      .param("id", id)
                      .update())
          .as(change)
          .hasMessageContaining("refunds_immutable");
    }
    assertThatThrownBy(() -> setState(id, "SUCCEEDED", null))
        .hasMessageContaining("refunds_succeeded_identified");
    setState(id, "SUCCEEDED", "re_1");
    assertThatThrownBy(() -> setState(id, "FAILED", "re_1"))
        .hasMessageContaining("refunds_legal_transition");
    assertThatThrownBy(() -> setState(id, "PENDING", "re_1"))
        .hasMessageContaining("refunds_legal_transition");
    assertThatThrownBy(() -> setState(id, "SUCCEEDED", "re_2"))
        .hasMessageContaining("refunds_immutable");

    UUID failed = insert(order, 100, null, "PENDING");
    setState(failed, "FAILED", null);
    assertThatThrownBy(() -> setState(failed, "PENDING", null))
        .hasMessageContaining("refunds_legal_transition");
    // A pending refund may learn the provider's id before it knows the outcome.
    UUID learning = insert(order, 100, null, "PENDING");
    setState(learning, "PENDING", "re_3");
    setState(learning, "SUCCEEDED", "re_3");
  }

  @Test
  void pendingRefundsAreScheduledAndOnlyPendingOnesLeased() {
    UUID order = completedOrder();
    UUID id = insert(order, 100, null, "PENDING");
    for (String change :
        List.of(
            "next_attempt_at = NULL",
            "attempt = -1",
            "lease_token = gen_random_uuid()",
            "lease_until = now()")) {
      assertThatThrownBy(
              () ->
                  jdbc.sql("UPDATE refunds SET " + change + " WHERE id = :id")
                      .param("id", id)
                      .update())
          .as(change)
          .hasMessageMatching(
              "(?s).*(refunds_pending_scheduled|refunds_attempt_nonnegative|refunds_lease_pair).*");
    }
    jdbc.sql(
            "UPDATE refunds SET lease_token = gen_random_uuid(), lease_until = now() WHERE id = :id")
        .param("id", id)
        .update();
    // Settling keeps neither a schedule nor a lease, and a failure says why.
    for (String settle :
        List.of(
            "state = 'FAILED', next_attempt_at = NULL, lease_token = NULL, lease_until = NULL",
            "state = 'SUCCEEDED', provider_refund_id = 're_1', lease_token = NULL,"
                + " lease_until = NULL",
            "state = 'SUCCEEDED', provider_refund_id = 're_1', next_attempt_at = NULL")) {
      assertThatThrownBy(
              () ->
                  jdbc.sql("UPDATE refunds SET " + settle + " WHERE id = :id")
                      .param("id", id)
                      .update())
          .as(settle)
          .hasMessageMatching(
              "(?s).*(refunds_failed_explained|refunds_pending_scheduled|refunds_settled_unleased).*");
    }
    jdbc.sql(
            """
            UPDATE refunds SET state = 'FAILED', failure_code = 'provider_refused',
              next_attempt_at = NULL, lease_token = NULL, lease_until = NULL
            WHERE id = :id
            """)
        .param("id", id)
        .update();
    assertThat(active(order)).isZero();
    assertThat(
            jdbc.sql(
                    """
                    SELECT count(*) FROM pg_constraint
                    WHERE conrelid = 'refunds'::regclass AND NOT convalidated
                    """)
                .query(Integer.class)
                .single())
        .isZero();
  }

  @Test
  void rejectsMalformedColumns() {
    UUID order = completedOrder();
    for (long amount : List.of(0L, -1L, 100_000_000L)) {
      assertThatThrownBy(() -> insert(order, amount, null, "PENDING"))
          .as("%d", amount)
          .hasMessageContaining("refunds_amount_range");
    }
    assertThatThrownBy(() -> insert(order, 100, null, "REFUNDED"))
        .hasMessageContaining("refunds_known_state");
    for (String lines :
        List.of(
            "{}",
            "[]",
            "[\"sku-1\"]",
            "[{\"sku\": \"sku-1\"}]",
            "[{\"quantity\": 1}]",
            "[{\"sku\": 1, \"quantity\": 1}]",
            "[{\"sku\": \"sku-1\", \"quantity\": \"1\"}]",
            "[{\"sku\": \"sku-1\", \"quantity\": 0}]",
            "[{\"sku\": \"sku-1\", \"quantity\": 1.5}]",
            "[{\"sku\": \"sku-1\", \"quantity\": 1}, {\"sku\": \"sku-2\", \"quantity\": -1}]")) {
      assertThatThrownBy(() -> insert(order, 100, lines, "PENDING"))
          .as(lines)
          .hasMessageContaining("refunds_lines_format");
    }
    assertThat(count(order)).isZero();
  }

  /** Runs {@code n} transactions that each insert a refund, all inserted before any commits. */
  private List<Boolean> race(UUID order, int n, long amount, int isolation) throws Exception {
    CyclicBarrier inserted = new CyclicBarrier(n);
    ExecutorService pool = Executors.newFixedThreadPool(n);
    try {
      List<Future<Boolean>> results = new ArrayList<>();
      for (int i = 0; i < n; i++) {
        results.add(
            pool.submit(
                () -> {
                  try {
                    tx(isolation)
                        .executeWithoutResult(
                            s -> {
                              insert(order, amount, null, "PENDING");
                              await(inserted);
                            });
                    return true;
                  } catch (DataIntegrityViolationException | ConcurrencyFailureException e) {
                    return false;
                  }
                }));
      }
      List<Boolean> committed = new ArrayList<>();
      for (Future<Boolean> f : results) {
        committed.add(f.get(30, TimeUnit.SECONDS));
      }
      return committed;
    } finally {
      pool.shutdownNow();
    }
  }

  private static void await(CyclicBarrier barrier) {
    try {
      barrier.await(10, TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private TransactionTemplate tx(int isolation) {
    TransactionTemplate t = new TransactionTemplate(txManager);
    t.setIsolationLevel(isolation);
    return t;
  }

  private UUID pendingOrder() {
    Order o = Orders.pending("acme", Instant.now());
    assertThat(repo.insert(o)).isTrue();
    return o.id();
  }

  private UUID completedOrder() {
    UUID id = pendingOrder();
    for (String state :
        List.of("INVENTORY_RESERVED", "PAYMENT_AUTHORIZED", "PAYMENT_CAPTURED", "COMPLETED")) {
      boolean done = state.equals("COMPLETED");
      jdbc.sql(
              """
              UPDATE orders SET state = :state,
                next_attempt_at = CASE WHEN :done THEN NULL ELSE now() END,
                captured_minor = CASE WHEN :done THEN amount_minor ELSE 0 END
              WHERE id = :id
              """)
          .param("state", state)
          .param("done", done)
          .param("id", id)
          .update();
    }
    return id;
  }

  private UUID insert(UUID order, long amount, String lines, String state) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO refunds (id, order_id, amount_minor, lines, state, provider_refund_id,
              failure_code, next_attempt_at, created_at, updated_at)
            VALUES (:id, :order, :amount, CAST(:lines AS jsonb), :state,
              CASE WHEN :state = 'SUCCEEDED' THEN 're_' || :id ELSE NULL END,
              CASE WHEN :state = 'FAILED' THEN 'test' ELSE NULL END,
              CASE WHEN :state = 'PENDING' THEN now() ELSE NULL END, now(), now())
            """)
        .param("id", id)
        .param("order", order)
        .param("amount", amount)
        .param("lines", lines)
        .param("state", state)
        .update();
    return id;
  }

  private void setState(UUID refund, String state, String providerRefundId) {
    jdbc.sql(
            """
            UPDATE refunds SET state = :state, provider_refund_id = :provider,
              failure_code = CASE WHEN :state = 'FAILED' THEN 'test' ELSE failure_code END,
              next_attempt_at = CASE WHEN :state = 'PENDING' THEN next_attempt_at ELSE NULL END,
              updated_at = now()
            WHERE id = :id
            """)
        .param("state", state)
        .param("provider", providerRefundId)
        .param("id", refund)
        .update();
  }

  private long active(UUID order) {
    return jdbc.sql(
            """
            SELECT coalesce(sum(amount_minor), 0) FROM refunds
            WHERE order_id = :order AND state <> 'FAILED'
            """)
        .param("order", order)
        .query(Long.class)
        .single();
  }

  private int count(UUID order) {
    return jdbc.sql("SELECT count(*) FROM refunds WHERE order_id = :order")
        .param("order", order)
        .query(Integer.class)
        .single();
  }
}
