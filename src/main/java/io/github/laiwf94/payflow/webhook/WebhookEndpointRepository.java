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
      "id, merchant_id, url, description, enabled_events, state, created_at, updated_at";

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

  /** Writes the endpoint's settings; empty when it doesn't exist for that merchant. */
  public Optional<WebhookEndpoint> update(WebhookEndpoint e) {
    return jdbc.sql(
            "UPDATE webhook_endpoints SET url = :url, description = :description,"
                + " enabled_events = :events, state = :state, updated_at = :updatedAt"
                + " WHERE id = :id AND merchant_id = :m AND deleted_at IS NULL RETURNING "
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
   * Marks the endpoint deleted and wipes its secret. False when it doesn't exist for that merchant
   * or was already deleted.
   */
  public boolean delete(String merchant, UUID id, Instant now) {
    return jdbc.sql(
                """
                UPDATE webhook_endpoints
                SET deleted_at = :now, updated_at = :now,
                    secret_key_id = NULL, secret_nonce = NULL, secret_ciphertext = NULL
                WHERE id = :id AND merchant_id = :m AND deleted_at IS NULL
                """)
            .param("id", id)
            .param("m", merchant)
            .param("now", ts(now))
            .update()
        == 1;
  }

  /** The sealed signing secret of a live endpoint. */
  public Optional<SecretBox.Sealed> secret(String merchant, UUID id) {
    return jdbc.sql(
            """
            SELECT secret_key_id, secret_nonce, secret_ciphertext FROM webhook_endpoints
            WHERE id = :id AND merchant_id = :m AND deleted_at IS NULL
            """)
        .param("id", id)
        .param("m", merchant)
        .query(
            (rs, n) ->
                new SecretBox.Sealed(
                    rs.getString("secret_key_id"),
                    rs.getBytes("secret_nonce"),
                    rs.getBytes("secret_ciphertext")))
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
    return new WebhookEndpoint(
        rs.getObject("id", UUID.class),
        rs.getString("merchant_id"),
        rs.getString("url"),
        rs.getString("description"),
        types,
        WebhookEndpoint.State.valueOf(rs.getString("state")),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getObject("updated_at", OffsetDateTime.class).toInstant());
  }

  private static OffsetDateTime ts(Instant i) {
    return i.atOffset(ZoneOffset.UTC);
  }
}
