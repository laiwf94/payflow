package io.github.laiwf94.payflow.webhook;

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

/**
 * Writes the webhook tables through raw SQL, bypassing the service, to show the database itself
 * refuses rows the API never writes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class WebhookSchemaTest {

  @Autowired JdbcClient jdbc;

  @BeforeEach
  void setUp() {
    Db.truncateAll(jdbc);
  }

  private static final String SECRET =
      "'test1', '\\x000102030405060708090a0b'::bytea, '\\x" + "00".repeat(48) + "'::bytea";

  /** Inserts an endpoint with the given column values, or throws what the database said. */
  private UUID endpoint(String url, String events, String state, String secret, String deletedAt) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO webhook_endpoints (id, merchant_id, url, enabled_events, state,"
                + " secret_key_id, secret_nonce, secret_ciphertext, created_at, updated_at,"
                + " deleted_at) VALUES (:id, 'acme', "
                + url
                + ", "
                + events
                + ", "
                + state
                + ", "
                + secret
                + ", now(), now(), "
                + deletedAt
                + ")")
        .param("id", id)
        .update();
    return id;
  }

  private UUID valid() {
    return endpoint("'https://h.example/in'", "ARRAY['*']", "'ENABLED'", SECRET, "NULL");
  }

  private void refused(String constraint, Runnable write) {
    assertThatThrownBy(write::run)
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining(constraint);
  }

  @Test
  void endpointRowsAreChecked() {
    valid();
    endpoint(
        "'http://[::1]:8080/x?y=1'",
        "ARRAY['order.created','refund.failed']",
        "'DISABLED'",
        SECRET,
        "NULL");
    endpoint("'https://h.example'", "ARRAY['*']", "'ENABLED'", "NULL, NULL, NULL", "now()");

    String ok = "'https://h.example'";
    refused(
        "webhook_endpoints_known_state",
        () -> endpoint(ok, "ARRAY['*']", "'PAUSED'", SECRET, "NULL"));
    for (String url :
        new String[] {
          "'ftp://h.example'",
          "'https://'",
          "'https://user@h.example'",
          "'https://h.example/#frag'",
          "'https://h.example/a b'",
          "'https://h.example/' || repeat('a', 2048)"
        }) {
      refused(
          "webhook_endpoints_url_format",
          () -> endpoint(url, "ARRAY['*']", "'ENABLED'", SECRET, "NULL"));
    }
    refused(
        "webhook_endpoints_events_count",
        () -> endpoint(ok, "ARRAY[]::text[]", "'ENABLED'", SECRET, "NULL"));
    refused(
        "webhook_endpoints_events_count",
        () -> endpoint(ok, "ARRAY['order.created', NULL]", "'ENABLED'", SECRET, "NULL"));
    refused(
        "webhook_endpoints_events_count",
        () ->
            endpoint(
                ok, "array_fill('order.created'::text, ARRAY[33])", "'ENABLED'", SECRET, "NULL"));
    refused(
        "webhook_endpoints_events_wildcard",
        () -> endpoint(ok, "ARRAY['*','order.created']", "'ENABLED'", SECRET, "NULL"));
    refused(
        "webhook_endpoints_secret",
        () -> endpoint(ok, "ARRAY['*']", "'ENABLED'", "NULL, NULL, NULL", "NULL"));
    refused(
        "webhook_endpoints_secret",
        () ->
            endpoint(
                ok,
                "ARRAY['*']",
                "'ENABLED'",
                "'k', '\\x00'::bytea, '\\x" + "00".repeat(48) + "'::bytea",
                "NULL"));
    refused(
        "webhook_endpoints_secret", () -> endpoint(ok, "ARRAY['*']", "'ENABLED'", SECRET, "now()"));
    refused(
        "webhook_endpoints_description_length",
        () ->
            jdbc.sql("UPDATE webhook_endpoints SET description = '' WHERE state = 'ENABLED'")
                .update());
  }

  @Test
  void deliveriesAndAttemptsAreChecked() {
    UUID hook = valid();
    UUID event = UUID.randomUUID();
    UUID delivery = delivery(hook, event, "'PENDING'", "now()");
    refused("webhook_deliveries_once", () -> delivery(hook, event, "'PENDING'", "now()"));
    refused(
        "webhook_deliveries_pending_scheduled",
        () -> delivery(hook, UUID.randomUUID(), "'PENDING'", "NULL"));
    refused(
        "webhook_deliveries_pending_scheduled",
        () -> delivery(hook, UUID.randomUUID(), "'SUCCEEDED'", "now()"));
    refused(
        "webhook_deliveries_known_state",
        () -> delivery(hook, UUID.randomUUID(), "'LOST'", "NULL"));
    refused(
        "webhook_deliveries_endpoint_id_fkey",
        () -> delivery(UUID.randomUUID(), UUID.randomUUID(), "'PENDING'", "now()"));

    attempt(delivery, "12", "200", "'ok'", "NULL");
    attempt(delivery, "5000", "NULL", "NULL", "'timed out'");
    refused("webhook_attempts_outcome", () -> attempt(delivery, "1", "500", "NULL", "'and why'"));
    refused("webhook_attempts_outcome", () -> attempt(delivery, "1", "NULL", "NULL", "NULL"));
    refused("webhook_attempts_status", () -> attempt(delivery, "1", "99", "NULL", "NULL"));
    refused("webhook_attempts_latency", () -> attempt(delivery, "-1", "200", "NULL", "NULL"));
    refused(
        "webhook_attempts_body_length",
        () -> attempt(delivery, "1", "200", "repeat('b', 1025)", "NULL"));
    refused(
        "webhook_attempts_error_length",
        () -> attempt(delivery, "1", "NULL", "NULL", "repeat('e', 513)"));
    assertThat(
            jdbc.sql("SELECT count(*) FROM webhook_attempts WHERE delivery_id = :d")
                .param("d", delivery)
                .query(Integer.class)
                .single())
        .isEqualTo(2);
  }

  private UUID delivery(UUID endpoint, UUID event, String state, String next) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO webhook_deliveries (id, endpoint_id, event_id, event_type, order_id,"
                + " order_seq, payload, state, next_attempt_at, created_at, updated_at)"
                + " VALUES (:id, :endpoint, :event, 'order.created', gen_random_uuid(), 1,"
                + " '{}'::jsonb, "
                + state
                + ", "
                + next
                + ", now(), now())")
        .param("id", id)
        .param("endpoint", endpoint)
        .param("event", event)
        .update();
    return id;
  }

  private void attempt(UUID delivery, String latency, String status, String body, String error) {
    jdbc.sql(
            "INSERT INTO webhook_attempts (delivery_id, attempted_at, latency_ms, response_status,"
                + " response_body, error) VALUES (:d, now(), "
                + latency
                + ", "
                + status
                + ", "
                + body
                + ", "
                + error
                + ")")
        .param("d", delivery)
        .update();
  }
}
