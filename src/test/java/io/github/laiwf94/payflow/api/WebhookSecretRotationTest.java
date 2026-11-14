package io.github.laiwf94.payflow.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.github.laiwf94.payflow.support.Api;
import io.github.laiwf94.payflow.support.Db;
import io.github.laiwf94.payflow.support.TestKeys;
import io.github.laiwf94.payflow.webhook.WebhookEndpointRepository;
import io.github.laiwf94.payflow.webhook.WebhookEndpointService;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/**
 * {@code POST /v1/webhook_endpoints/{id}/rotate_secret}: a new secret shown once and stored only
 * sealed, the replaced one signing beside it until it expires, at most two at a time, and the
 * validation of how long the old one lasts.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebhookSecretRotationTest {

  private static final String HOOKS = "/v1/webhook_endpoints";

  @LocalServerPort int port;
  @Autowired JdbcClient jdbc;
  @Autowired WebhookEndpointService service;
  @Autowired WebhookEndpointRepository repo;

  private Api api;

  @BeforeEach
  void setUp() {
    Db.truncateAll(jdbc);
    api = new Api(port, TestKeys.ACME);
  }

  /** Registers an endpoint; returns its id and first secret. */
  private String[] endpoint() {
    Api.Response r =
        api.post(HOOKS, "{\"url\":\"https://hooks.example.com/in\",\"enabled_events\":[\"*\"]}");
    assertThat(r.status()).as(r.body()).isEqualTo(201);
    return new String[] {r.json().get("id").asString(), r.json().get("secret").asString()};
  }

  private List<String> signing(String id, Instant at) {
    return service.signingSecrets("acme", UUID.fromString(id), at);
  }

  private String dump() {
    return String.join(
        "\n",
        jdbc.sql(
                "SELECT row_to_json(w)::text FROM webhook_endpoints w"
                    + " UNION ALL SELECT row_to_json(k)::text FROM idempotency_keys k")
            .query(String.class)
            .list());
  }

  @Test
  void theNewSecretIsShownOnceAndBothSignUntilTheOldOneExpires() {
    String[] e = endpoint();
    String id = e[0];
    String old = e[1];
    Instant before = Instant.now();
    Api.Response r =
        api.post(HOOKS + "/" + id + "/rotate_secret", null, "Idempotency-Key", "rot-1");
    assertThat(r.status()).as(r.body()).isEqualTo(200);
    JsonNode body = r.json();
    String fresh = body.get("secret").asString();
    assertThat(fresh).matches("whsec_[A-Za-z0-9_-]{43}").isNotEqualTo(old);
    assertThat(body.get("id").asString()).isEqualTo(id);
    assertThat(body.get("state").asString()).isEqualTo("ENABLED");
    Instant expires = Instant.parse(body.get("previous_secret_expires_at").asString());
    assertThat(expires)
        .isCloseTo(before.plus(Duration.ofDays(1)), within(10, ChronoUnit.SECONDS))
        .isAfter(before.plus(Duration.ofDays(1)));

    // Reads show when the old secret stops, never a secret.
    JsonNode read = api.get(HOOKS + "/" + id).json();
    assertThat(read.has("secret")).isFalse();
    assertThat(read.get("previous_secret_expires_at").asString())
        .isEqualTo(body.get("previous_secret_expires_at").asString());
    assertThat(api.get(HOOKS).body()).doesNotContain(fresh).doesNotContain(old);

    // Both sign, the new one first, until the old one expires; then only the new one.
    assertThat(signing(id, Instant.now())).containsExactly(fresh, old);
    assertThat(signing(id, expires.minusMillis(1))).containsExactly(fresh, old);
    assertThat(signing(id, expires)).containsExactly(fresh);

    // Neither secret is in the database in the clear, the stored answer for replays included.
    assertThat(dump())
        .doesNotContain(fresh)
        .doesNotContain(fresh.substring(6))
        .doesNotContain(old.substring(6));
    assertThat(
            jdbc.sql("SELECT response_body FROM idempotency_keys WHERE idem_key = 'rot-1'")
                .query(String.class)
                .single())
        .startsWith("sealed:v1:");

    // A retry with the same key is the same rotation: same answer, no third secret.
    Api.Response again =
        api.post(HOOKS + "/" + id + "/rotate_secret", null, "Idempotency-Key", "rot-1");
    assertThat(again.header("Idempotent-Replayed")).isEqualTo("true");
    assertThat(again.json()).isEqualTo(body);
    assertThat(signing(id, Instant.now())).containsExactly(fresh, old);
  }

  @Test
  void rotatingAgainDropsTheOldestSoAtMostTwoSign() {
    String[] e = endpoint();
    String id = e[0];
    String second =
        api.post(HOOKS + "/" + id + "/rotate_secret", null).json().get("secret").asString();
    Api.Response third =
        api.post(HOOKS + "/" + id + "/rotate_secret", "{\"previous_secret_expires_in\":3600}");
    assertThat(third.status()).as(third.body()).isEqualTo(200);
    String s3 = third.json().get("secret").asString();
    assertThat(signing(id, Instant.now())).containsExactly(s3, second).doesNotContain(e[1]);
    assertThat(Instant.parse(third.json().get("previous_secret_expires_at").asString()))
        .isCloseTo(Instant.now().plus(Duration.ofHours(1)), within(10, ChronoUnit.SECONDS));
  }

  @Test
  void zeroStopsTheOldSecretAtOnceAndSevenDaysIsTheLongest() {
    String id = endpoint()[0];
    Api.Response r =
        api.post(HOOKS + "/" + id + "/rotate_secret", "{\"previous_secret_expires_in\":0}");
    assertThat(r.status()).as(r.body()).isEqualTo(200);
    assertThat(r.json().has("previous_secret_expires_at")).isFalse();
    assertThat(signing(id, Instant.now())).containsExactly(r.json().get("secret").asString());
    assertThat(previousColumns(id)).containsOnlyNulls();

    // A rotation with no body at all, sent as JSON with nothing in it, is the default day.
    Api.Response empty = api.post(HOOKS + "/" + id + "/rotate_secret", null);
    assertThat(empty.status()).as(empty.body()).isEqualTo(200);
    assertThat(empty.json().has("previous_secret_expires_at")).isTrue();
    Api.Response nullTtl =
        api.post(HOOKS + "/" + id + "/rotate_secret", "{\"previous_secret_expires_in\":null}");
    assertThat(nullTtl.status()).as(nullTtl.body()).isEqualTo(200);

    Api.Response week =
        api.post(HOOKS + "/" + id + "/rotate_secret", "{\"previous_secret_expires_in\":604800}");
    assertThat(week.status()).as(week.body()).isEqualTo(200);
    assertThat(Instant.parse(week.json().get("previous_secret_expires_at").asString()))
        .isCloseTo(Instant.now().plus(Duration.ofDays(7)), within(10, ChronoUnit.SECONDS));
  }

  @Test
  void aRefusedRotationChangesNothing() {
    String[] e = endpoint();
    String id = e[0];
    for (String bad :
        List.of(
            "{\"previous_secret_expires_in\":-1}",
            "{\"previous_secret_expires_in\":604801}",
            "{\"previous_secret_expires_in\":1.5}",
            "{\"previous_secret_expires_in\":\"60\"}",
            "{\"previous_secret_expires_in\":true}",
            "{\"previous_secret_expires_in\":9223372036854775808}",
            "{\"expires_in\":60}",
            "[]",
            "{")) {
      Api.Response r = api.post(HOOKS + "/" + id + "/rotate_secret", bad);
      assertThat(r.status()).as(bad + " -> " + r.body()).isEqualTo(400);
      assertThat(r.json().get("error").get("code").asString()).isEqualTo("invalid_argument");
    }
    assertThat(signing(id, Instant.now())).containsExactly(e[1]);
    assertThat(previousColumns(id)).containsOnlyNulls();
  }

  @Test
  void onlyTheMerchantsOwnLiveEndpointsRotate() {
    String[] e = endpoint();
    String id = e[0];
    Api globex = new Api(port, TestKeys.GLOBEX);
    assertThat(globex.post(HOOKS + "/" + id + "/rotate_secret", null).status()).isEqualTo(404);
    assertThat(api.post(HOOKS + "/" + UUID.randomUUID() + "/rotate_secret", null).status())
        .isEqualTo(404);
    assertThat(api.post(HOOKS + "/not-a-uuid/rotate_secret", null).status()).isEqualTo(404);
    assertThat(signing(id, Instant.now())).containsExactly(e[1]);

    // A deleted endpoint loses both secrets, and can't be rotated.
    assertThat(api.post(HOOKS + "/" + id + "/rotate_secret", null).status()).isEqualTo(200);
    assertThat(api.send("DELETE", HOOKS + "/" + id, null).status()).isEqualTo(200);
    assertThat(previousColumns(id)).containsOnlyNulls();
    assertThat(api.post(HOOKS + "/" + id + "/rotate_secret", null).status()).isEqualTo(404);
  }

  @Test
  void aDisabledEndpointCanRotateAndKeepsItsState() {
    String id = endpoint()[0];
    assertThat(api.post(HOOKS + "/" + id, "{\"state\":\"DISABLED\"}").status()).isEqualTo(200);
    Api.Response r = api.post(HOOKS + "/" + id + "/rotate_secret", null);
    assertThat(r.status()).as(r.body()).isEqualTo(200);
    assertThat(r.json().get("state").asString()).isEqualTo("DISABLED");
    assertThat(r.json().get("disabled_reason").asString()).isEqualTo("merchant");
  }

  @Test
  void expiredPreviousSecretsAreHiddenAndThenWiped() throws Exception {
    String id = endpoint()[0];
    Api.Response r =
        api.post(HOOKS + "/" + id + "/rotate_secret", "{\"previous_secret_expires_in\":1}");
    assertThat(r.status()).isEqualTo(200);
    Instant expires = Instant.parse(r.json().get("previous_secret_expires_at").asString());
    assertThat(repo.wipeExpiredSecrets(expires.minusMillis(1))).isZero();
    assertThat(previousColumns(id)).doesNotContainNull();

    Thread.sleep(Math.max(0, Duration.between(Instant.now(), expires).toMillis() + 50));
    assertThat(api.get(HOOKS + "/" + id).json().has("previous_secret_expires_at")).isFalse();
    assertThat(signing(id, Instant.now())).hasSize(1);
    // The sealed bytes stay until the sweep, which wipes only what has expired.
    String other = endpoint()[0];
    assertThat(api.post(HOOKS + "/" + other + "/rotate_secret", null).status()).isEqualTo(200);
    assertThat(service.wipeExpiredSecrets()).isEqualTo(1);
    assertThat(previousColumns(id)).containsOnlyNulls();
    assertThat(previousColumns(other)).doesNotContainNull();
  }

  private List<Object> previousColumns(String id) {
    Map<String, Object> row =
        jdbc.sql(
                """
                SELECT previous_secret_key_id, previous_secret_nonce, previous_secret_ciphertext,
                    previous_secret_expires_at
                FROM webhook_endpoints WHERE id = :id
                """)
            .param("id", UUID.fromString(id))
            .query()
            .singleRow();
    return new java.util.ArrayList<>(row.values());
  }
}
