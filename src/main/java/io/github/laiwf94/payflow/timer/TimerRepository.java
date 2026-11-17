package io.github.laiwf94.payflow.timer;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Timers in Postgres. Every write the worker makes is a compare-and-set on the timer's key and
 * {@code lease_token}, so a worker whose lease was taken over, or whose timer was rescheduled or
 * cancelled under it, changes nothing.
 */
public class TimerRepository {

  /** The most of a handler's error that is kept; the column's CHECK allows 512. */
  static final int MAX_ERROR = 512;

  private final JdbcClient jdbc;

  public TimerRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Adds a timer unless the subject already has one of this kind. True when it was added. */
  boolean insertIfAbsent(String kind, UUID subject, Instant dueAt, Instant now) {
    return jdbc.sql(
                """
                INSERT INTO timers (kind, subject_id, due_at, next_attempt_at, created_at,
                    updated_at)
                VALUES (:kind, :subject, :due, :due, :now, :now)
                ON CONFLICT (kind, subject_id) DO NOTHING
                """)
            .param("kind", kind)
            .param("subject", subject)
            .param("due", ts(dueAt))
            .param("now", ts(now))
            .update()
        == 1;
  }

  /**
   * Sets the subject's timer of this kind to {@code dueAt}, adding it if there is none. A lease on
   * the old one is dropped, which fences out a fire already claimed for the old time; its attempts
   * and error start over.
   */
  void upsert(String kind, UUID subject, Instant dueAt, Instant now) {
    jdbc.sql(
            """
            INSERT INTO timers (kind, subject_id, due_at, next_attempt_at, created_at, updated_at)
            VALUES (:kind, :subject, :due, :due, :now, :now)
            ON CONFLICT (kind, subject_id) DO UPDATE SET
                due_at = EXCLUDED.due_at, next_attempt_at = EXCLUDED.due_at, attempt = 0,
                lease_token = NULL, last_error = NULL, updated_at = EXCLUDED.updated_at
            """)
        .param("kind", kind)
        .param("subject", subject)
        .param("due", ts(dueAt))
        .param("now", ts(now))
        .update();
  }

  /** Removes the subject's timer of this kind. False when there was none. */
  boolean delete(String kind, UUID subject) {
    return jdbc.sql("DELETE FROM timers WHERE kind = :kind AND subject_id = :subject")
            .param("kind", kind)
            .param("subject", subject)
            .update()
        == 1;
  }

  public Optional<Timer> find(String kind, UUID subject) {
    return jdbc.sql("SELECT * FROM timers WHERE kind = :kind AND subject_id = :subject")
        .param("kind", kind)
        .param("subject", subject)
        .query((rs, n) -> map(rs))
        .optional();
  }

  /**
   * Leases up to {@code limit} due timers of {@code kinds}, earliest first. {@code SKIP LOCKED}
   * lets every instance poll without waiting on the others. The lease pushes {@code
   * next_attempt_at} out, so a held timer leaves the due range of the index rather than being
   * skipped by every poll until it fires; if its worker dies, it is due again when the lease ends.
   * A claim of a timer that still carries a token took over from a worker that never released it.
   */
  List<TimerClaim> claimDue(Collection<String> kinds, Instant now, Duration lease, int limit) {
    if (kinds.isEmpty()) {
      return List.of();
    }
    return jdbc.sql(
            """
            WITH due AS (
                SELECT kind, subject_id, lease_token AS expired_token
                FROM timers
                WHERE next_attempt_at <= :now AND kind IN (:kinds)
                ORDER BY next_attempt_at
                LIMIT :limit
                FOR UPDATE SKIP LOCKED
            )
            UPDATE timers t
            SET lease_token = gen_random_uuid(), next_attempt_at = :leaseUntil
            FROM due
            WHERE t.kind = due.kind AND t.subject_id = due.subject_id
            RETURNING t.*, due.expired_token IS NOT NULL AS recovered
            """)
        .param("now", ts(now))
        .param("kinds", kinds)
        .param("leaseUntil", ts(now.plus(lease)))
        .param("limit", limit)
        .query(
            (rs, n) ->
                new TimerClaim(
                    map(rs), rs.getObject("lease_token", UUID.class), rs.getBoolean("recovered")))
        .list();
  }

  /**
   * Deletes a leased timer, to fire it in the same transaction. Empty when the lease is no longer
   * this worker's: another worker took it over, or it was rescheduled or cancelled.
   */
  Optional<Timer> take(String kind, UUID subject, UUID leaseToken) {
    return jdbc.sql(
            """
            DELETE FROM timers
            WHERE kind = :kind AND subject_id = :subject AND lease_token = :token
            RETURNING *
            """)
        .param("kind", kind)
        .param("subject", subject)
        .param("token", leaseToken)
        .query((rs, n) -> map(rs))
        .optional();
  }

  /** Schedules the next try after a failed fire and gives the lease back. False if it was lost. */
  boolean retry(
      String kind,
      UUID subject,
      UUID leaseToken,
      int attempt,
      Instant nextAttemptAt,
      String error,
      Instant now) {
    return jdbc.sql(
                """
                UPDATE timers SET attempt = :attempt, next_attempt_at = :next, last_error = :error,
                    lease_token = NULL, updated_at = :now
                WHERE kind = :kind AND subject_id = :subject AND lease_token = :token
                """)
            .param("attempt", attempt)
            .param("next", ts(nextAttemptAt))
            .param("error", truncate(error))
            .param("now", ts(now))
            .param("kind", kind)
            .param("subject", subject)
            .param("token", leaseToken)
            .update()
        == 1;
  }

  /** Gives back a claim that was never started, due again at once. */
  boolean release(String kind, UUID subject, UUID leaseToken, Instant now) {
    return jdbc.sql(
                """
                UPDATE timers SET lease_token = NULL, next_attempt_at = :now
                WHERE kind = :kind AND subject_id = :subject AND lease_token = :token
                """)
            .param("now", ts(now))
            .param("kind", kind)
            .param("subject", subject)
            .param("token", leaseToken)
            .update()
        == 1;
  }

  /**
   * Hands back a timer whose fire may still be running, as if this worker had died: the token is
   * replaced, fencing out the fire, and the timer is due for every clock, so the next poll claims
   * it at once and is told it was {@code recovered}.
   *
   * <p>A fire that already deleted the row holds its lock until it commits or rolls back. Waiting
   * for it could hold up a shutdown for as long as the handler takes, so such a row is skipped
   * instead: if the fire commits the timer is gone, and if it rolls back the lease runs out.
   */
  boolean abandon(String kind, UUID subject, UUID leaseToken) {
    return jdbc.sql(
                """
                WITH mine AS (
                    SELECT kind, subject_id FROM timers
                    WHERE kind = :kind AND subject_id = :subject AND lease_token = :token
                    FOR UPDATE SKIP LOCKED
                )
                UPDATE timers t
                SET lease_token = gen_random_uuid(), next_attempt_at = '-infinity'
                FROM mine
                WHERE t.kind = mine.kind AND t.subject_id = mine.subject_id
                """)
            .param("kind", kind)
            .param("subject", subject)
            .param("token", leaseToken)
            .update()
        == 1;
  }

  private static String truncate(String s) {
    String v = s == null || s.isBlank() ? "failed" : s;
    return v.length() <= MAX_ERROR ? v : v.substring(0, MAX_ERROR);
  }

  private static Timer map(ResultSet rs) throws SQLException {
    return new Timer(
        rs.getString("kind"),
        rs.getObject("subject_id", UUID.class),
        instant(rs, "due_at"),
        rs.getInt("attempt"),
        rs.getString("last_error"),
        instant(rs, "created_at"),
        instant(rs, "updated_at"));
  }

  private static OffsetDateTime ts(Instant i) {
    return i.atOffset(ZoneOffset.UTC);
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    return rs.getObject(column, OffsetDateTime.class).toInstant();
  }
}
