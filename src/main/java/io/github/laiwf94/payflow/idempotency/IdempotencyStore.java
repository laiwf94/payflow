package io.github.laiwf94.payflow.idempotency;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Idempotency keys in Postgres. */
@Component
public class IdempotencyStore {

  /** What a request may do with its key. */
  public sealed interface Claim {
    /** First use (or a takeover of an abandoned one): run the request holding {@code token}. */
    record Proceed(UUID token) implements Claim {}

    /** Completed before: send this response again. */
    record Replay(int status, String body, String location) implements Claim {}

    /** Another request with this key is running right now. */
    record InProgress() implements Claim {}

    /** The key was used for a different request. */
    record Mismatch() implements Claim {}
  }

  private record Row(
      String method,
      String path,
      String hashHex,
      UUID lockToken,
      Instant lockedUntil,
      Integer status,
      String body,
      String location) {}

  private final JdbcClient jdbc;
  private final Clock clock;
  private final IdempotencyProperties props;

  public IdempotencyStore(JdbcClient jdbc, Clock clock, IdempotencyProperties props) {
    this.jdbc = jdbc;
    this.clock = clock;
    this.props = props;
  }

  /** Claims the key. Runs in its own short transaction so other requests see the claim. */
  public Claim claim(String merchant, String key, String method, String path, byte[] hash) {
    Claim c = tryClaim(merchant, key, method, path, hash);
    // Null means the row vanished between our insert and our read: a failed request released it.
    // One more try settles it; if it vanishes again, something else is racing on this key.
    return c != null
        ? c
        : Objects.requireNonNullElse(
            tryClaim(merchant, key, method, path, hash), new Claim.InProgress());
  }

  private Claim tryClaim(String merchant, String key, String method, String path, byte[] hash) {
    Instant now = clock.instant();
    UUID token = UUID.randomUUID();
    int inserted =
        jdbc.sql(
                """
                INSERT INTO idempotency_keys (merchant_id, idem_key, request_method, request_path,
                    request_hash, lock_token, locked_until, created_at)
                VALUES (:merchant, :key, :method, :path, :hash, :token, :until, :now)
                ON CONFLICT DO NOTHING
                """)
            .param("merchant", merchant)
            .param("key", key)
            .param("method", method)
            .param("path", path)
            .param("hash", hash)
            .param("token", token)
            .param("until", now.plus(props.lockTimeout()).atOffset(ZoneOffset.UTC))
            .param("now", now.atOffset(ZoneOffset.UTC))
            .update();
    if (inserted == 1) {
      return new Claim.Proceed(token);
    }
    Optional<Row> existing =
        jdbc.sql(
                """
                SELECT request_method, request_path, encode(request_hash, 'hex'), lock_token,
                       locked_until,
                       response_status, response_body, response_location
                FROM idempotency_keys WHERE merchant_id = :merchant AND idem_key = :key
                """)
            .param("merchant", merchant)
            .param("key", key)
            .query(
                (rs, i) ->
                    new Row(
                        rs.getString(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getObject(4, UUID.class),
                        rs.getObject(5, java.time.OffsetDateTime.class) == null
                            ? null
                            : rs.getObject(5, java.time.OffsetDateTime.class).toInstant(),
                        (Integer) rs.getObject(6),
                        rs.getString(7),
                        rs.getString(8)))
            .optional();
    if (existing.isEmpty()) {
      return null;
    }
    Row r = existing.get();
    if (!r.method().equals(method)
        || !r.path().equals(path)
        || !r.hashHex().equals(HexFormat.of().formatHex(hash))) {
      return new Claim.Mismatch();
    }
    if (r.status() != null) {
      return new Claim.Replay(r.status(), r.body(), r.location());
    }
    if (r.lockedUntil().isAfter(now)) {
      return new Claim.InProgress();
    }
    // The request that held the key died before finishing. Its work rolled back with it, because
    // the response is stored in the same transaction as the work, so starting over is safe.
    int taken =
        jdbc.sql(
                """
                UPDATE idempotency_keys SET lock_token = :token, locked_until = :until
                WHERE merchant_id = :merchant AND idem_key = :key AND lock_token = :old
                """)
            .param("token", token)
            .param("until", now.plus(props.lockTimeout()).atOffset(ZoneOffset.UTC))
            .param("merchant", merchant)
            .param("key", key)
            .param("old", r.lockToken())
            .update();
    return taken == 1 ? new Claim.Proceed(token) : new Claim.InProgress();
  }

  /**
   * Stores the response. Call inside the request's own transaction, so the response is saved if and
   * only if the request's work commits. Returns false if the lock was lost meanwhile.
   */
  public boolean complete(
      String merchant, String key, UUID token, int status, String body, String location) {
    return jdbc.sql(
                """
                UPDATE idempotency_keys SET response_status = :status, response_body = :body,
                    response_location = :location, completed_at = :now,
                    lock_token = NULL, locked_until = NULL
                WHERE merchant_id = :merchant AND idem_key = :key AND lock_token = :token
                """)
            .param("status", status)
            .param("body", body)
            .param("location", location)
            .param("now", clock.instant().atOffset(ZoneOffset.UTC))
            .param("merchant", merchant)
            .param("key", key)
            .param("token", token)
            .update()
        == 1;
  }

  /** Forgets an in-progress claim, after a failure that left nothing behind. */
  public void release(String merchant, String key, UUID token) {
    jdbc.sql(
            """
            DELETE FROM idempotency_keys
            WHERE merchant_id = :merchant AND idem_key = :key AND lock_token = :token
            """)
        .param("merchant", merchant)
        .param("key", key)
        .param("token", token)
        .update();
  }

  /** Keys older than the retention are forgotten; a retry after that is a new request. */
  @Scheduled(fixedDelay = 10, timeUnit = java.util.concurrent.TimeUnit.MINUTES)
  public int expire() {
    return jdbc.sql(
            """
            DELETE FROM idempotency_keys WHERE (merchant_id, idem_key) IN (
                SELECT merchant_id, idem_key FROM idempotency_keys
                WHERE created_at < :cutoff LIMIT 10000)
            """)
        .param("cutoff", clock.instant().minus(props.retention()).atOffset(ZoneOffset.UTC))
        .update();
  }

  Duration lockTimeout() {
    return props.lockTimeout();
  }
}
