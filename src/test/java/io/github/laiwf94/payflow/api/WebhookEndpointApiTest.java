package io.github.laiwf94.payflow.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.laiwf94.payflow.support.Api;
import io.github.laiwf94.payflow.support.Db;
import io.github.laiwf94.payflow.support.TestKeys;
import io.github.laiwf94.payflow.webhook.WebhookEndpointService;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/**
 * {@code /v1/webhook_endpoints}: registration with a secret shown once and kept only sealed, the
 * listing, changes, deletion, merchant isolation, the per-merchant limit, and the validation
 * matrix.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebhookEndpointApiTest {

  private static final String HOOKS = "/v1/webhook_endpoints";
  private static final String BODY =
      """
      {"url":"https://hooks.example.com/payflow","enabled_events":["*"],"description":"main"}
      """;

  @LocalServerPort int port;
  @Autowired JdbcClient jdbc;
  @Autowired WebhookEndpointService service;

  private Api api;

  @BeforeEach
  void setUp() {
    Db.truncateAll(jdbc);
    api = new Api(port, TestKeys.ACME);
  }

  private Api.Response create(String body) {
    return api.post(HOOKS, body);
  }

  private static String body(String url, String events) {
    return "{\"url\":\"" + url + "\",\"enabled_events\":" + events + "}";
  }

  @Test
  void registeringShowsTheSecretOnceAndStoresItOnlySealed() {
    Api.Response created = api.post(HOOKS, BODY, "Idempotency-Key", "hook-1");
    assertThat(created.status()).as(created.body()).isEqualTo(201);
    JsonNode e = created.json();
    String id = e.get("id").asString();
    String secret = e.get("secret").asString();
    assertThat(secret).matches("whsec_[A-Za-z0-9_-]{43}");
    assertThat(created.header("Location")).isEqualTo(HOOKS + "/" + id);
    assertThat(e.get("url").asString()).isEqualTo("https://hooks.example.com/payflow");
    assertThat(e.get("enabled_events").get(0).asString()).isEqualTo("*");
    assertThat(e.get("state").asString()).isEqualTo("ENABLED");
    assertThat(e.get("description").asString()).isEqualTo("main");
    assertThat(e.get("created_at").asString()).isEqualTo(e.get("updated_at").asString());

    // Never again: not on a read, not in the listing.
    Api.Response read = api.get(HOOKS + "/" + id);
    assertThat(read.status()).isEqualTo(200);
    assertThat(read.json().has("secret")).isFalse();
    assertThat(read.body()).doesNotContain(secret);
    assertThat(api.get(HOOKS).body()).doesNotContain(secret).contains(id);

    // Nowhere in the database in the clear, including the stored answer for replays.
    String dump =
        String.join(
            "\n",
            jdbc.sql(
                    "SELECT row_to_json(w)::text FROM webhook_endpoints w"
                        + " UNION ALL SELECT row_to_json(k)::text FROM idempotency_keys k")
                .query(String.class)
                .list());
    assertThat(dump).contains(id).doesNotContain(secret).doesNotContain(secret.substring(6));
    Map<String, Object> row =
        jdbc.sql("SELECT secret_key_id, secret_ciphertext FROM webhook_endpoints WHERE id = :id")
            .param("id", UUID.fromString(id))
            .query()
            .singleRow();
    assertThat(row.get("secret_key_id")).isEqualTo("test1");
    assertThat(new String((byte[]) row.get("secret_ciphertext"), StandardCharsets.ISO_8859_1))
        .doesNotContain(secret);
    assertThat(
            jdbc.sql("SELECT response_body FROM idempotency_keys WHERE idem_key = 'hook-1'")
                .query(String.class)
                .single())
        .startsWith("sealed:v1:test1:");

    // The sealed copy opens to sign deliveries.
    assertThat(service.signingSecret("acme", UUID.fromString(id))).isEqualTo(secret);
  }

  @Test
  void aRetryWithTheSameKeyReplaysTheSameEndpointAndSecret() {
    Api.Response first = api.post(HOOKS, BODY, "Idempotency-Key", "same");
    Api.Response again = api.post(HOOKS, BODY, "Idempotency-Key", "same");
    assertThat(again.status()).isEqualTo(201);
    assertThat(again.header("Idempotent-Replayed")).isEqualTo("true");
    assertThat(again.body()).isEqualTo(first.body());
    assertThat(again.header("Location")).isEqualTo(first.header("Location"));
    assertThat(api.get(HOOKS).json().get("data").size()).as("one endpoint, not two").isEqualTo(1);

    Api.Response other = api.post(HOOKS, BODY, "Idempotency-Key", "different");
    assertThat(other.json().get("secret").asString())
        .as("every endpoint has its own secret")
        .isNotEqualTo(first.json().get("secret").asString());
  }

  @Test
  void theListingIsTheMerchantsLiveEndpointsOldestFirst() {
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      ids.add(
          create(body("https://h" + i + ".example.com", "[\"order.completed\"]"))
              .json()
              .get("id")
              .asString());
    }
    new Api(port, TestKeys.GLOBEX).post(HOOKS, BODY);
    JsonNode data = api.get(HOOKS).json().get("data");
    assertThat(data).extracting(n -> n.get("id").asString()).containsExactlyElementsOf(ids);
    assertThat(data).allSatisfy(n -> assertThat(n.has("secret")).isFalse());

    assertThat(api.send("DELETE", HOOKS + "/" + ids.get(1), null).status()).isEqualTo(200);
    assertThat(api.get(HOOKS).json().get("data"))
        .extracting(n -> n.get("id").asString())
        .containsExactly(ids.get(0), ids.get(2));
  }

  @Test
  void anUpdateChangesWhatItNamesAndKeepsTheRest() {
    JsonNode created = create(BODY).json();
    String path = HOOKS + "/" + created.get("id").asString();
    String secret = created.get("secret").asString();

    Api.Response disabled = api.post(path, "{\"state\":\"DISABLED\"}");
    assertThat(disabled.status()).as(disabled.body()).isEqualTo(200);
    assertThat(disabled.json().get("state").asString()).isEqualTo("DISABLED");
    assertThat(disabled.json().get("url").asString())
        .isEqualTo("https://hooks.example.com/payflow");
    assertThat(disabled.json().get("description").asString()).isEqualTo("main");
    assertThat(disabled.json().has("secret")).isFalse();
    assertThat(java.time.Instant.parse(disabled.json().get("updated_at").asString()))
        .isAfter(java.time.Instant.parse(created.get("updated_at").asString()));

    Api.Response changed =
        api.post(
            path,
            """
            {"url":"HTTPS://New.Example.com/in","enabled_events":["refund.failed","order.failed",
             "refund.failed"],"description":"","state":"ENABLED"}
            """);
    assertThat(changed.status()).as(changed.body()).isEqualTo(200);
    JsonNode c = changed.json();
    assertThat(c.get("url").asString()).isEqualTo("https://new.example.com/in");
    assertThat(c.get("enabled_events"))
        .extracting(JsonNode::asString)
        .containsExactly("refund.failed", "order.failed");
    assertThat(c.has("description")).as("an empty description clears it").isFalse();
    assertThat(c.get("state").asString()).isEqualTo("ENABLED");
    assertThat(api.get(path).json()).isEqualTo(c);
    assertThat(service.signingSecret("acme", UUID.fromString(c.get("id").asString())))
        .as("the secret doesn't change")
        .isEqualTo(secret);

    for (String bad :
        List.of(
            "{}",
            "{\"url\":\"ftp://x.example\"}",
            "{\"enabled_events\":[]}",
            "{\"enabled_events\":[\"order.shipped\"]}",
            "{\"state\":\"PAUSED\"}",
            "{\"state\":\"enabled\"}",
            "{\"secret\":\"whsec_mine\"}",
            "{\"description\":\"" + "d".repeat(257) + "\"}")) {
      Api.Response r = api.post(path, bad);
      assertThat(r.status()).as(bad + " " + r.body()).isEqualTo(400);
      assertThat(r.errorCode()).isEqualTo("invalid_argument");
    }
    assertThat(api.get(path).json()).as("a refused update changes nothing").isEqualTo(c);
  }

  @Test
  void aDeletedEndpointIsGoneAndItsSecretWiped() {
    String id = create(BODY).json().get("id").asString();
    String path = HOOKS + "/" + id;
    Api.Response deleted = api.send("DELETE", path, null);
    assertThat(deleted.status()).isEqualTo(200);
    assertThat(deleted.json().get("id").asString()).isEqualTo(id);
    assertThat(deleted.json().get("deleted").asBoolean()).isTrue();

    assertThat(api.get(path).status()).isEqualTo(404);
    assertThat(api.get(path).json().at("/error/message").asString())
        .isEqualTo("webhook endpoint not found");
    assertThat(api.post(path, "{\"state\":\"ENABLED\"}").status()).isEqualTo(404);
    assertThat(api.send("DELETE", path, null).status()).as("deleting again").isEqualTo(404);
    assertThat(api.get(HOOKS).json().get("data").size()).isZero();

    Map<String, Object> row =
        jdbc.sql(
                "SELECT deleted_at, secret_key_id, secret_nonce, secret_ciphertext"
                    + " FROM webhook_endpoints WHERE id = :id")
            .param("id", UUID.fromString(id))
            .query()
            .singleRow();
    assertThat(row.get("deleted_at")).isNotNull();
    assertThat(row.get("secret_key_id")).isNull();
    assertThat(row.get("secret_nonce")).isNull();
    assertThat(row.get("secret_ciphertext")).isNull();
  }

  @Test
  void oneMerchantCanNeitherSeeNorChangeAnothersEndpoints() {
    String id = create(BODY).json().get("id").asString();
    String path = HOOKS + "/" + id;
    Api globex = new Api(port, TestKeys.GLOBEX);
    assertThat(globex.get(path).status()).isEqualTo(404);
    assertThat(globex.post(path, "{\"state\":\"DISABLED\"}").status()).isEqualTo(404);
    assertThat(globex.send("DELETE", path, null).status()).isEqualTo(404);
    assertThat(globex.get(HOOKS).json().get("data").size()).isZero();
    assertThat(api.get(path).json().get("state").asString()).isEqualTo("ENABLED");

    assertThat(new Api(port, null).get(HOOKS).status()).isEqualTo(401);
    assertThat(new Api(port, null).send("DELETE", path, null).status()).isEqualTo(401);
    assertThat(api.get(path).status()).isEqualTo(200);
    for (String malformed : List.of("nope", id.toUpperCase(java.util.Locale.ROOT) + "x", "1")) {
      assertThat(api.get(HOOKS + "/" + malformed).status()).as(malformed).isEqualTo(404);
    }
  }

  @Test
  void aMerchantHasAtMostSixteenEvenWhenRacing() throws Exception {
    for (int i = 0; i < 12; i++) {
      assertThat(create(BODY).status()).isEqualTo(201);
    }
    int racers = 10;
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Api.Response>> results = new ArrayList<>();
    try (ExecutorService pool = Executors.newFixedThreadPool(racers)) {
      for (int i = 0; i < racers; i++) {
        results.add(
            pool.submit(
                () -> {
                  start.await();
                  return create(BODY);
                }));
      }
      start.countDown();
      int created = 0;
      int refused = 0;
      for (Future<Api.Response> f : results) {
        Api.Response r = f.get();
        if (r.status() == 201) {
          created++;
        } else {
          assertThat(r.status()).as(r.body()).isEqualTo(409);
          assertThat(r.errorCode()).isEqualTo("webhook_endpoint_limit_reached");
          refused++;
        }
      }
      assertThat(created).isEqualTo(4);
      assertThat(refused).isEqualTo(6);
    }
    assertThat(api.get(HOOKS).json().get("data").size()).isEqualTo(16);
    assertThat(new Api(port, TestKeys.GLOBEX).post(HOOKS, BODY).status())
        .as("the limit is per merchant")
        .isEqualTo(201);

    String first = api.get(HOOKS).json().get("data").get(0).get("id").asString();
    assertThat(api.send("DELETE", HOOKS + "/" + first, null).status()).isEqualTo(200);
    assertThat(create(BODY).status()).as("a deleted endpoint frees its place").isEqualTo(201);
    assertThat(create(BODY).status()).isEqualTo(409);
  }

  @Test
  void registrationIsValidated() {
    String tooLongUrl = "https://h.example/" + "a".repeat(2048);
    List<String> bad =
        List.of(
            "{\"enabled_events\":[\"*\"]}",
            "{\"url\":\"https://h.example\"}",
            "{\"url\":null,\"enabled_events\":[\"*\"]}",
            body("https://h.example", "[]"),
            body("https://h.example", "\"*\""),
            body("https://h.example", "[\"*\",\"order.created\"]"),
            body("https://h.example", "[\"order.shipped\"]"),
            body("https://h.example", "[null]"),
            body("https://h.example", "[1]"),
            body("https://h.example", "[" + "\"order.created\",".repeat(32) + "\"order.created\"]"),
            "{\"url\":7,\"enabled_events\":[\"*\"]}",
            body("ftp://h.example", "[\"*\"]"),
            body("https://user:pw@h.example", "[\"*\"]"),
            body("https://h.example/#x", "[\"*\"]"),
            body("h.example/in", "[\"*\"]"),
            body("", "[\"*\"]"),
            body(tooLongUrl, "[\"*\"]"),
            "{\"url\":\"https://h.example\",\"enabled_events\":[\"*\"],\"state\":\"ENABLED\"}",
            "{\"url\":\"https://h.example\",\"enabled_events\":[\"*\"],\"secret\":\"whsec_x\"}",
            "{\"url\":\"https://h.example\",\"enabled_events\":[\"*\"],\"description\":\"a\\nb\"}",
            "{\"url\":\"https://h.example\",\"enabled_events\":[\"*\"],\"description\":\""
                + "d".repeat(257)
                + "\"}",
            "{\"url\":\"https://h.example\",\"enabled_events\":[\"*\"],\"description\":5}",
            "[]",
            "not json");
    for (String b : bad) {
      Api.Response r = create(b);
      assertThat(r.status()).as(b + " -> " + r.body()).isEqualTo(400);
      assertThat(r.errorCode()).isEqualTo("invalid_argument");
    }
    assertThat(jdbc.sql("SELECT count(*) FROM webhook_endpoints").query(Integer.class).single())
        .as("nothing refused was stored")
        .isZero();

    // The edges that are fine.
    String longest = "https://h.example/" + "a".repeat(2048 - 18);
    assertThat(create(body(longest, "[\"*\"]")).status()).isEqualTo(201);
    assertThat(
            create(
                    "{\"url\":\"http://h.example:8080\",\"enabled_events\":[\"order.created\"],"
                        + "\"description\":\""
                        + "d".repeat(256)
                        + "\"}")
                .status())
        .isEqualTo(201);
    Api.Response repeated =
        create(body("https://h.example", "[\"refund.failed\",\"refund.failed\"]"));
    assertThat(repeated.status()).as(repeated.body()).isEqualTo(201);
    assertThat(repeated.json().get("enabled_events").size()).isEqualTo(1);
    assertThat(create(body("https://h.example", "[\"*\"]")).json().has("description")).isFalse();
  }
}
