package io.github.laiwf94.payflow.webhook;

import io.github.laiwf94.payflow.security.SecretBox;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Webhook endpoints in Postgres. Every read and write is scoped to a merchant and skips deleted
 * endpoints, so one merchant can never see or change another's, and a deleted endpoint is gone as
 * far as the API is concerned while its row stays for its delivery history.
 */
@Repository
public class WebhookEndpointRepository {

  private static final String COLUMNS =
      "id, merchant_id, url, description, enabled_events, state, disabled_reason, failing_since,"
          + " previous_secret_expires_at, created_at, updated_at";

  private final JdbcClient jdbc;

  public WebhookEndpointRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Serializes the transactions that create endpoints for {@code merchant} until they commit, so
   * the count the per-merchant limit takes can't be raced past.
   */
  public void lockMerchant(String merchant) {
    jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
        .param("key", "webhook_endpoints\n" + merchant)
        .query((rs, n) -> 1)
        .single();
  }

  public int countLive(String merchant) {
    return jdbc.sql(
            "SELECT count(*) FROM webhook_endpoints WHERE merchant_id = :m AND deleted_at IS NULL")
        .param("m", merchant)
        .query(Integer.class)
        .single();
  }

  public void insert(WebhookEndpoint e, SecretBox.Sealed secret) {
    jdbc.sql(
            """
            INSERT INTO webhook_endpoints (id, merchant_id, url, description, enabled_events,
                state, secret_key_id, secret_nonce, secret_ciphertext, created_at, updated_at)
            VALUES (:id, :merchant, :url, :description, :events, :state, :keyId, :nonce,
                :ciphertext, :createdAt, :updatedAt)
            """)
        .param("id", e.id())
        .param("merchant", e.merchantId())
        .param("url", e.url())
        .param("description", e.description())
        .param("events", e.enabledEvents().toArray(String[]::new))
        .param("state", e.state().name())
        .param("keyId", secret.keyId())
        .param("nonce", secret.nonce())
        .param("ciphertext", secret.ciphertext())
        .param("createdAt", ts(e.createdAt()))
        .param("updatedAt", ts(e.updatedAt()))
        .update();
  }

  public Optional<WebhookEndpoint> find(String merchant, UUID id) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM webhook_endpoints"
                + " WHERE id = :id AND merchant_id = :m AND deleted_at IS NULL")
        .param("id", id)
        .param("m", merchant)
        .query((rs, n) -> map(rs))
        .optional();
  }

  /** The merchant's live endpoints, oldest first. */
  public List<WebhookEndpoint> list(String merchant) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM webhook_endpoints WHERE merchant_id = :m AND deleted_at IS NULL"
                + " ORDER BY created_at, id")
        .param("m", merchant)
        .query((rs, n) -> map(rs))
        .list();
  }

  /**
   * Writes the endpoint's settings; empty when it doesn't exist for that merchant. Disabling it
   * records that the merchant did, unless payflow already had; enabling a disabled endpoint clears
   * the reason and its run of failures, so it gets the full time to recover again.
   */
  public Optional<WebhookEndpoint> update(WebhookEndpoint e) {
    return jdbc.sql(
            """
            UPDATE webhook_endpoints SET url = :url, description = :description,
                enabled_events = :events, state = :state,
                disabled_reason = CASE
                    WHEN :state = 'ENABLED' THEN NULL
                    WHEN state = 'DISABLED' THEN COALESCE(disabled_reason, 'merchant')
                    ELSE 'merchant' END,
                failing_since = CASE
                    WHEN :state = 'ENABLED' AND state = 'DISABLED' THEN NULL
                    ELSE failing_since END,
                updated_at = :updatedAt
            WHERE id = :id AND merchant_id = :m AND deleted_at IS NULL
            RETURNING\s"""
                + COLUMNS)
        .param("id", e.id())
        .param("m", e.merchantId())
        .param("url", e.url())
        .param("description", e.description())
        .param("events", e.enabledEvents().toArray(String[]::new))
        .param("state", e.state().name())
        .param("updatedAt", ts(e.updatedAt()))
        .query((rs, n) -> map(rs))
        .optional();
  }

  /**
   * Marks the endpoint deleted and wipes its secrets. False when it doesn't exist for that merchant
   * or was already deleted.
   */
  public boolean delete(String merchant, UUID id, Instant now) {
    return jdbc.sql(
                """
                UPDATE webhook_endpoints
                SET deleted_at = :now, updated_at = :now,
                    secret_key_id = NULL, secret_nonce = NULL, secret_ciphertext = NULL,
                    previous_secret_key_id = NULL, previous_secret_nonce = NULL,
                    previous_secret_ciphertext = NULL, previous_secret_expires_at = NULL
                WHERE id = :id AND merchant_id = :m AND deleted_at IS NULL
                """)
            .param("id", id)
            .param("m", merchant)
            .param("now", ts(now))
            .update()
        == 1;
  }

  /**
   * The sealed secrets that sign a live endpoint's deliveries at {@code now}: the current one, then
   * the previous one while it has not expired. Empty when there is no such endpoint.
   */
  public List<SecretBox.Sealed> secrets(String merchant, UUID id, Instant now) {
    return jdbc.sql(
            """
            SELECT secret_key_id, secret_nonce, secret_ciphertext,
                previous_secret_key_id, previous_secret_nonce, previous_secret_ciphertext,
                previous_secret_expires_at > :now AS previous_signs
            FROM webhook_endpoints
            WHERE id = :id AND merchant_id = :m AND deleted_at IS NULL
            """)
        .param("id", id)
        .param("m", merchant)
        .param("now", ts(now))
        .query(
            (rs, n) -> {
              SecretBox.Sealed current =
                  new SecretBox.Sealed(
                      rs.getString("secret_key_id"),
                      rs.getBytes("secret_nonce"),
                      rs.getBytes("secret_ciphertext"));
              if (!rs.getBoolean("previous_signs")) {
                return List.of(current);
              }
              return List.of(
                  current,
                  new SecretBox.Sealed(
                      rs.getString("previous_secret_key_id"),
                      rs.getBytes("previous_secret_nonce"),
                      rs.getBytes("previous_secret_ciphertext")));
            })
        .optional()
        .orElse(List.of());
  }

  /**
   * Makes {@code fresh} the endpoint's secret. With {@code previousUntil}, the secret it replaces
   * keeps signing beside it until then, and a previous secret from an earlier rotation is dropped,
   * so at most two ever sign; without, the old secret stops signing at once. Empty when the
   * endpoint doesn't exist for that merchant.
   */
  public Optional<WebhookEndpoint> rotate(
      String merchant, UUID id, SecretBox.Sealed fresh, Instant previousUntil, Instant now) {
    boolean keep = previousUntil != null;
    return jdbc.sql(
            """
            UPDATE webhook_endpoints SET
                previous_secret_key_id = CASE WHEN :keep THEN secret_key_id END,
                previous_secret_nonce = CASE WHEN :keep THEN secret_nonce END,
                previous_secret_ciphertext = CASE WHEN :keep THEN secret_ciphertext END,
                previous_secret_expires_at = CASE WHEN :keep THEN :until END,
                secret_key_id = :keyId, secret_nonce = :nonce, secret_ciphertext = :ciphertext,
                updated_at = :now
            WHERE id = :id AND merchant_id = :m AND deleted_at IS NULL
            RETURNING\s"""
                + COLUMNS)
        .param("id", id)
        .param("m", merchant)
        .param("keep", keep)
        .param("until", ts(keep ? previousUntil : now))
        .param("keyId", fresh.keyId())
        .param("nonce", fresh.nonce())
        .param("ciphertext", fresh.ciphertext())
        .param("now", ts(now))
        .query((rs, n) -> map(rs))
        .optional();
  }

  /**
   * Wipes previous secrets that have expired, and any left on a deleted endpoint. Returns how many
   * endpoints it wiped.
   */
  public int wipeExpiredSecrets(Instant now) {
    return jdbc.sql(
            """
            UPDATE webhook_endpoints SET
                previous_secret_key_id = NULL, previous_secret_nonce = NULL,
                previous_secret_ciphertext = NULL, previous_secret_expires_at = NULL
            WHERE previous_secret_expires_at IS NOT NULL
              AND (previous_secret_expires_at <= :now OR deleted_at IS NOT NULL)
            """)
        .param("now", ts(now))
        .update();
  }

  /** Ends the endpoint's run of failures. Writes nothing when it had none. */
  public boolean markHealthy(UUID id) {
    return jdbc.sql(
                "UPDATE webhook_endpoints SET failing_since = NULL"
                    + " WHERE id = :id AND failing_since IS NOT NULL")
            .param("id", id)
            .update()
        == 1;
  }

  /**
   * Starts the endpoint's run of failures at {@code at}, unless one is already running. Writes
   * nothing then, so an endpoint that keeps failing doesn't become a row every attempt updates.
   */
  public void markFailing(UUID id, Instant at) {
    jdbc.sql(
            "UPDATE webhook_endpoints SET failing_since = :at"
                + " WHERE id = :id AND failing_since IS NULL AND deleted_at IS NULL")
        .param("id", id)
        .param("at", ts(at))
        .update();
  }

  /**
   * Disables an enabled endpoint whose run of failures began at {@code cutoff} or earlier. Returns
   * the endpoint when this call disabled it; only one caller can.
   */
  public Optional<WebhookEndpoint> disableIfFailingSince(UUID id, Instant cutoff, Instant now) {
    return jdbc.sql(
            """
            UPDATE webhook_endpoints
            SET state = 'DISABLED', disabled_reason = 'failing', updated_at = :now
            WHERE id = :id AND state = 'ENABLED' AND deleted_at IS NULL
              AND failing_since <= :cutoff
            RETURNING\s"""
                + COLUMNS)
        .param("id", id)
        .param("cutoff", ts(cutoff))
        .param("now", ts(now))
        .query((rs, n) -> map(rs))
        .optional();
  }

  /** What the sealed secret is bound to: its endpoint and merchant. */
  static String secretContext(String merchant, UUID id) {
    return "webhook_endpoints.secret\n" + merchant + "\n" + id;
  }

  private static WebhookEndpoint map(ResultSet rs) throws SQLException {
    Array events = rs.getArray("enabled_events");
    List<String> types = Arrays.asList((String[]) events.getArray());
    events.free();
    String reason = rs.getString("disabled_reason");
    return new WebhookEndpoint(
        rs.getObject("id", UUID.class),
        rs.getString("merchant_id"),
        rs.getString("url"),
        rs.getString("description"),
        types,
        WebhookEndpoint.State.valueOf(rs.getString("state")),
        reason == null
            ? null
            : WebhookEndpoint.DisabledReason.valueOf(reason.toUpperCase(Locale.ROOT)),
        instant(rs, "failing_since"),
        instant(rs, "previous_secret_expires_at"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getObject("updated_at", OffsetDateTime.class).toInstant());
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
    return t == null ? null : t.toInstant();
  }

  private static OffsetDateTime ts(Instant i) {
    return i.atOffset(ZoneOffset.UTC);
  }
}
