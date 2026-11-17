package io.github.laiwf94.payflow.timer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.laiwf94.payflow.support.Db;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Raw SQL against the timers table, past any service: the database refuses malformed timers. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class TimerSchemaTest {

  @Autowired JdbcClient jdbc;

  @BeforeEach
  void clean() {
    Db.truncateAll(jdbc);
  }

  private void insert(String kind, String due, String next, int attempt, String error) {
    jdbc.sql(
            """
            INSERT INTO timers (kind, subject_id, due_at, next_attempt_at, attempt, last_error,
                created_at, updated_at)
            VALUES (:kind, :subject, CAST(:due AS timestamptz), CAST(:next AS timestamptz),
                :attempt, :error, now(), now())
            """)
        .param("kind", kind)
        .param("subject", UUID.randomUUID())
        .param("due", due)
        .param("next", next)
        .param("attempt", attempt)
        .param("error", error)
        .update();
  }

  @Test
  void aWellFormedTimerIsAcceptedAndAHandBackMayUseMinusInfinity() {
    insert("order.reservation_expiry", "2026-11-23T10:00:00Z", "2026-11-23T10:00:00Z", 0, null);
    insert("order.unpaid", "2026-11-23T10:00:00Z", "-infinity", 2, "boom");
    assertThat(jdbc.sql("SELECT count(*) FROM timers").query(Integer.class).single()).isEqualTo(2);
  }

  @Test
  void malformedTimersAreRefused() {
    String t = "2026-11-23T10:00:00Z";
    assertRefused(() -> insert("Order.Expiry", t, t, 0, null), "timers_kind_format");
    assertRefused(() -> insert("a".repeat(64), t, t, 0, null), "timers_kind_format");
    assertRefused(() -> insert("x", t, t, -1, null), "timers_attempt_nonnegative");
    assertRefused(() -> insert("x", "infinity", t, 0, null), "timers_due_finite");
    assertRefused(() -> insert("x", "-infinity", t, 0, null), "timers_due_finite");
    assertRefused(() -> insert("x", t, "infinity", 0, null), "timers_next_attempt");
    assertRefused(() -> insert("x", t, t, 1, null), "timers_error_explained");
    assertRefused(() -> insert("x", t, t, 0, "boom"), "timers_error_explained");
    assertRefused(() -> insert("x", t, t, 1, "e".repeat(513)), "timers_error_length");
  }

  @Test
  void aSubjectHasAtMostOneTimerOfEachKind() {
    UUID subject = UUID.randomUUID();
    String sql =
        """
        INSERT INTO timers (kind, subject_id, due_at, next_attempt_at, created_at, updated_at)
        VALUES (:kind, :subject, now(), now(), now(), now())
        """;
    jdbc.sql(sql).param("kind", "a").param("subject", subject).update();
    jdbc.sql(sql).param("kind", "b").param("subject", subject).update();
    assertThatThrownBy(() -> jdbc.sql(sql).param("kind", "a").param("subject", subject).update())
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("timers_pkey");
  }

  /**
   * With a realistic table, 20,000 timers of two kinds of which a few dozen are due, the poll reads
   * the due ones from {@code timers_due} in order, without sorting the table.
   */
  @Test
  void thePollIsAnIndexScanInDueOrder() {
    jdbc.sql(
            """
            INSERT INTO timers (kind, subject_id, due_at, next_attempt_at, created_at, updated_at)
            SELECT CASE WHEN g % 2 = 0 THEN 'a' ELSE 'b' END, gen_random_uuid(),
                   now() + g * interval '1 second' - interval '50 seconds',
                   now() + g * interval '1 second' - interval '50 seconds', now(), now()
            FROM generate_series(1, 20000) g
            """)
        .update();
    jdbc.sql("ANALYZE timers").update();
    String plan =
        String.join(
            "\n",
            jdbc.sql(
                    """
                    EXPLAIN SELECT kind, subject_id FROM timers
                    WHERE next_attempt_at <= now() AND kind IN ('a', 'b')
                    ORDER BY next_attempt_at LIMIT 256 FOR UPDATE SKIP LOCKED
                    """)
                .query(String.class)
                .list());
    assertThat(plan).contains("Index Scan using timers_due").doesNotContain("Sort");
  }

  private static void assertRefused(Runnable insert, String constraint) {
    assertThatThrownBy(insert::run)
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining(constraint);
  }
}
