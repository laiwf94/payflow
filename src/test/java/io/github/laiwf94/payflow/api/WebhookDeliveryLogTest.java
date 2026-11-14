package io.github.laiwf94.payflow.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.github.laiwf94.payflow.support.Api;
import io.github.laiwf94.payflow.support.Db;
import io.github.laiwf94.payflow.support.TestKeys;
import io.github.laiwf94.payflow.webhook.WebhookDeliveryRepository;
import io.github.laiwf94.payflow.webhook.WebhookDispatcher;
import io.github.laiwf94.payflow.webhook.WebhookSignature;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The delivery log, replays and test pings, over HTTP against real Postgres, with the context's own
 * dispatcher sending to a stand-in merchant server on loopback.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "payflow.webhooks.allowed-networks=127.0.0.1/32")
class WebhookDeliveryLogTest {

  private static final String HOOKS = "/v1/webhook_endpoints";

  @LocalServerPort int port;
  @Autowired JdbcClient jdbc;
  @Autowired TransactionTemplate tx;
  @Autowired WebhookDeliveryRepository deliveries;
  @Autowired WebhookDispatcher dispatcher;
  @Autowired ObjectMapper json;

  private Api api;
  private HttpServer server;
  private final List<Map<String, String>> received = new CopyOnWriteArrayList<>();
  private final Map<String, AtomicInteger> statusByPath = new ConcurrentHashMap<>();

  @BeforeEach
  void setUp() throws Exception {
    Db.truncateAll(jdbc);
    api = new Api(port, TestKeys.ACME);
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.createContext(
        "/",
        ex -> {
          try (InputStream in = ex.getRequestBody()) {
            Map<String, String> r = new ConcurrentHashMap<>();
            ex.getRequestHeaders()
                .forEach((k, v) -> r.put(k.toLowerCase(Locale.ROOT), String.join(",", v)));
            r.put(":path", ex.getRequestURI().getPath());
            r.put(":body", new String(in.readAllBytes(), StandardCharsets.UTF_8));
            received.add(r);
            int status =
                statusByPath
                    .computeIfAbsent(ex.getRequestURI().getPath(), p -> new AtomicInteger(200))
                    .get();
            // A long answer, so the log has to cut it.
            byte[] body =
                ("status " + status + " " + "x".repeat(3000)).getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, body.length);
            try (OutputStream out = ex.getResponseBody()) {
              out.write(body);
            }
          } finally {
            ex.close();
          }
        });
    server.start();
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  private String url(String path) {
    return "http://127.0.0.1:" + server.getAddress().getPort() + path;
  }

  /** Registers an endpoint; returns its id and secret. */
  private String[] endpoint(Api as, String path) {
    Api.Response r = as.post(HOOKS, "{\"url\":\"" + url(path) + "\",\"enabled_events\":[\"*\"]}");
    assertThat(r.status()).as(r.body()).isEqualTo(201);
    return new String[] {r.json().get("id").asString(), r.json().get("secret").asString()};
  }

  /** An event of {@code order} handed to the merchant's endpoints at {@code at}; its event id. */
  private UUID event(String merchant, UUID order, long seq, String type, Instant at) {
    UUID eventId = UUID.randomUUID();
    String payload =
        json.writeValueAsString(
            Map.of(
                "event_id", eventId,
                "event_type", type,
                "order_id", order,
                "merchant_id", merchant,
                "seq", seq));
    long id =
        jdbc.sql(
                """
                INSERT INTO outbox (event_id, aggregate_id, aggregate_seq, event_type, payload,
                    correlation_id, created_at)
                VALUES (:e, :o, :s, :t, CAST(:p AS jsonb), 'corr', :at) RETURNING id
                """)
            .param("e", eventId)
            .param("o", order)
            .param("s", seq)
            .param("t", type)
            .param("p", payload)
            .param("at", at.atOffset(ZoneOffset.UTC))
            .query(Long.class)
            .single();
    tx.executeWithoutResult(s -> deliveries.enqueue(List.of(id), at));
    return eventId;
  }

  private void sendAllDue() {
    while (dispatcher.runOnce() > 0) {
      Thread.onSpinWait();
    }
  }

  private String deliveryOf(String endpoint, UUID event) {
    return jdbc.sql("SELECT id FROM webhook_deliveries WHERE endpoint_id = :e AND event_id = :ev")
        .param("e", UUID.fromString(endpoint))
        .param("ev", event)
        .query(String.class)
        .single();
  }

  private static Instant micros(Instant t) {
    return t.truncatedTo(ChronoUnit.MICROS);
  }

  @Test
  void theLogShowsEachDeliveryWithItsLatestAttemptAndTheDetailEveryAttempt() {
    String[] hook = endpoint(api, "/hook");
    UUID order = UUID.randomUUID();
    Instant t0 = micros(Instant.now());
    UUID created = event("acme", order, 1, "order.created", t0);
    UUID completed = event("acme", order, 2, "order.completed", t0.plusMillis(1));
    UUID never = event("acme", UUID.randomUUID(), 1, "order.created", t0.plusMillis(2));
    statusByPath.put("/hook", new AtomicInteger(500));
    sendAllDue(); // created fails; completed waits behind it; never fails
    statusByPath.get("/hook").set(204);
    jdbc.sql("UPDATE webhook_deliveries SET next_attempt_at = now() WHERE state = 'PENDING'")
        .update();
    sendAllDue();

    Api.Response log = api.get(HOOKS + "/" + hook[0] + "/deliveries");
    assertThat(log.status()).as(log.body()).isEqualTo(200);
    JsonNode data = log.json().get("data");
    assertThat(data)
        .extracting(d -> d.get("event_id").asString())
        .containsExactly(never.toString(), completed.toString(), created.toString());
    assertThat(log.json().get("has_more").asBoolean()).isFalse();
    assertThat(log.json().has("next_cursor")).isFalse();
    JsonNode first = data.get(2);
    assertThat(first.get("id").asString()).isEqualTo(deliveryOf(hook[0], created));
    assertThat(first.get("endpoint_id").asString()).isEqualTo(hook[0]);
    assertThat(first.get("event_type").asString()).isEqualTo("order.created");
    assertThat(first.get("order_id").asString()).isEqualTo(order.toString());
    assertThat(first.get("state").asString()).isEqualTo("SUCCEEDED");
    assertThat(first.get("attempts").asInt()).isEqualTo(2);
    assertThat(first.has("next_attempt_at")).as("settled").isFalse();
    assertThat(first.has("replayed_at")).isFalse();
    JsonNode last = first.get("last_attempt");
    assertThat(last.get("response_status").asInt()).isEqualTo(204);
    assertThat(last.get("latency_ms").asInt()).isNotNegative();
    assertThat(last.has("error")).isFalse();
    assertThat(data.get(1).get("attempts").asInt()).as("completed waited, then went once").isOne();

    Api.Response detail =
        api.get(HOOKS + "/" + hook[0] + "/deliveries/" + first.get("id").asString());
    assertThat(detail.status()).as(detail.body()).isEqualTo(200);
    JsonNode d = detail.json();
    assertThat(d.get("payload").get("event_id").asString()).isEqualTo(created.toString());
    assertThat(d.get("payload").get("seq").asInt()).isOne();
    JsonNode attempts = d.get("attempt_log");
    assertThat(attempts).hasSize(2);
    assertThat(attempts.get(0).get("response_status").asInt()).isEqualTo(500);
    // The body is kept in part only: the first 1,024 characters of a 3,000-character answer.
    assertThat(attempts.get(0).get("response_body").asString())
        .hasSize(1024)
        .startsWith("status 500 xxx");
    assertThat(attempts.get(1).get("response_status").asInt()).isEqualTo(204);
    assertThat(Instant.parse(attempts.get(0).get("attempted_at").asString()))
        .isBefore(Instant.parse(attempts.get(1).get("attempted_at").asString()));
    assertThat(d.get("last_attempt")).isEqualTo(attempts.get(1));

    // An attempt with no answer says why instead.
    jdbc.sql("UPDATE webhook_endpoints SET url = :u WHERE id = :id")
        .param("u", "http://127.0.0.1:1/closed")
        .param("id", UUID.fromString(hook[0]))
        .update();
    UUID refused = event("acme", UUID.randomUUID(), 1, "order.created", t0.plusMillis(3));
    sendAllDue();
    JsonNode noAnswer =
        api.get(HOOKS + "/" + hook[0] + "/deliveries?event_id=" + refused)
            .json()
            .get("data")
            .get(0);
    assertThat(noAnswer.get("state").asString()).isEqualTo("PENDING");
    assertThat(noAnswer.has("next_attempt_at")).isTrue();
    assertThat(noAnswer.get("last_attempt").has("response_status")).isFalse();
    assertThat(noAnswer.get("last_attempt").get("error").asString()).startsWith("connect");
  }

  @Test
  void theLogPagesNewestFirstWithACursorSignedOverTheEndpointAndFilters() {
    String[] hook = endpoint(api, "/a");
    String[] other = endpoint(api, "/b");
    Instant t0 = micros(Instant.now().minus(Duration.ofHours(1)));
    List<UUID> events = new ArrayList<>();
    for (int i = 0; i < 25; i++) {
      // Pairs share a timestamp, so pages end inside ties.
      events.add(event("acme", UUID.randomUUID(), 1, "order.created", t0.plusMillis(i / 2)));
    }
    String path = HOOKS + "/" + hook[0] + "/deliveries";
    List<String> seen = new ArrayList<>();
    String cursor = null;
    int pages = 0;
    do {
      Api.Response page = api.get(path + "?limit=7" + (cursor == null ? "" : "&cursor=" + cursor));
      assertThat(page.status()).as(page.body()).isEqualTo(200);
      page.json().get("data").forEach(d -> seen.add(d.get("event_id").asString()));
      // More events arrive while paging; they are newer and never show up on later pages.
      event("acme", UUID.randomUUID(), 1, "order.created", micros(Instant.now()));
      cursor =
          page.json().get("has_more").asBoolean()
              ? page.json().get("next_cursor").asString()
              : null;
      pages++;
    } while (cursor != null);
    assertThat(pages).isEqualTo(4);
    assertThat(seen).hasSize(25).doesNotHaveDuplicates();
    assertThat(new HashSet<>(seen))
        .isEqualTo(new HashSet<>(events.stream().map(UUID::toString).toList()));
    List<String> expectedOrder =
        jdbc.sql(
                """
                SELECT event_id::text FROM webhook_deliveries
                WHERE endpoint_id = :e AND created_at < :cut
                ORDER BY created_at DESC, id DESC
                """)
            .param("e", UUID.fromString(hook[0]))
            .param("cut", t0.plusSeconds(1).atOffset(ZoneOffset.UTC))
            .query(String.class)
            .list();
    assertThat(seen).isEqualTo(expectedOrder);

    // Filters: by event, by time range, by state.
    assertThat(api.get(path + "?event_id=" + events.get(3)).json().get("data"))
        .extracting(d -> d.get("event_id").asString())
        .containsExactly(events.get(3).toString());
    String range =
        "?created_gte=" + t0.plusMillis(2) + "&created_lt=" + t0.plusMillis(4) + "&limit=100";
    assertThat(api.get(path + range).json().get("data")).hasSize(4);
    assertThat(api.get(path + "?state=SUCCEEDED,FAILED").json().get("data")).isEmpty();
    assertThat(api.get(path + "?state=PENDING&limit=100").json().get("data")).hasSize(29);

    // A cursor belongs to its endpoint, merchant and filters, and to this listing.
    String c = api.get(path + "?limit=2").json().get("next_cursor").asString();
    assertThat(api.get(path + "?limit=2&cursor=" + c).status()).isEqualTo(200);
    for (Api.Response misuse :
        List.of(
            api.get(HOOKS + "/" + other[0] + "/deliveries?cursor=" + c),
            api.get(path + "?state=PENDING&cursor=" + c),
            api.get("/v1/orders?cursor=" + c),
            api.get(path + "?cursor=" + c.substring(1)))) {
      assertThat(misuse.status()).as(misuse.body()).isEqualTo(400);
      assertThat(misuse.errorCode()).isEqualTo("invalid_cursor");
    }
    api.post("/v1/orders", Api.orderBody());
    api.post("/v1/orders", Api.orderBody());
    String orderCursor = api.get("/v1/orders?limit=1").json().get("next_cursor").asString();
    assertThat(api.get(path + "?cursor=" + orderCursor).errorCode()).isEqualTo("invalid_cursor");

    for (String bad :
        List.of(
            "?limit=0",
            "?limit=101",
            "?status=FAILED",
            "?state=failed",
            "?state=DONE",
            "?event_id=nope",
            "?event_id=" + UUID.randomUUID().toString().toUpperCase(Locale.ROOT),
            "?created_gte=yesterday",
            "?created_gte=2026-11-02T09:30:00",
            "?created_gte=2026-11-02T10:00:00Z&created_lt=2026-11-02T09:00:00Z",
            "?limit=1&limit=2")) {
      Api.Response r = api.get(path + bad);
      assertThat(r.status()).as(bad).isEqualTo(400);
      assertThat(r.errorCode()).as(bad).isEqualTo("invalid_argument");
    }
  }

  @Test
  void anotherMerchantsEndpointOrDeliveryIsNotFound() {
    String[] hook = endpoint(api, "/hook");
    UUID event = event("acme", UUID.randomUUID(), 1, "order.created", micros(Instant.now()));
    String delivery = deliveryOf(hook[0], event);
    Api globex = api.as(TestKeys.GLOBEX);
    String[] theirs = endpoint(globex, "/theirs");
    for (Api.Response r :
        List.of(
            globex.get(HOOKS + "/" + hook[0] + "/deliveries"),
            globex.get(HOOKS + "/" + hook[0] + "/deliveries/" + delivery),
            api.get(HOOKS + "/" + theirs[0] + "/deliveries/" + delivery),
            api.get(HOOKS + "/" + hook[0] + "/deliveries/" + UUID.randomUUID()),
            api.get(HOOKS + "/" + hook[0] + "/deliveries/not-a-uuid"),
            api.get(HOOKS + "/not-a-uuid/deliveries/not-a-uuid"),
            globex.post(HOOKS + "/" + hook[0] + "/replay", "{\"event_id\":\"" + event + "\"}"),
            globex.post(HOOKS + "/" + hook[0] + "/ping", null))) {
      assertThat(r.status()).as(r.body()).isEqualTo(404);
      assertThat(r.errorCode()).isEqualTo("not_found");
    }
    assertThat(api.get(HOOKS + "/" + hook[0] + "/deliveries/not-a-uuid").json().toString())
        .contains("webhook delivery not found");
    assertThat(api.get(HOOKS + "/not-a-uuid/deliveries/not-a-uuid").json().toString())
        .contains("webhook endpoint not found");
    // A deleted endpoint's log is gone from the API with it.
    api.send("DELETE", HOOKS + "/" + hook[0], null);
    assertThat(api.get(HOOKS + "/" + hook[0] + "/deliveries").status()).isEqualTo(404);
  }

  @Test
  void aReplayedDeliveryIsSentAgainWithARetryWindowOfItsOwn() throws Exception {
    String[] hook = endpoint(api, "/hook");
    UUID order = UUID.randomUUID();
    // Four days ago: past the 3-day window, so it was given up.
    Instant old = micros(Instant.now().minus(Duration.ofDays(4)));
    UUID event = event("acme", order, 1, "order.created", old);
    statusByPath.put("/hook", new AtomicInteger(503));
    sendAllDue();
    String delivery = deliveryOf(hook[0], event);
    String path = HOOKS + "/" + hook[0];
    assertThat(api.get(path + "/deliveries/" + delivery).json().get("state").asString())
        .isEqualTo("FAILED");

    Api.Response r = api.post(path + "/replay", "{\"event_id\":\"" + event + "\"}");
    assertThat(r.status()).as(r.body()).isEqualTo(200);
    assertThat(r.json().get("replayed").asInt()).isOne();
    assertThat(r.json().get("already_pending").asInt()).isZero();
    JsonNode replayed = api.get(path + "/deliveries/" + delivery).json();
    assertThat(replayed.get("state").asString()).isEqualTo("PENDING");
    assertThat(Instant.parse(replayed.get("replayed_at").asString()))
        .isAfter(Instant.now().minusSeconds(60));

    // Still failing: the attempt is retried, not given up, because the window starts at the
    // replay rather than four days ago.
    sendAllDue();
    JsonNode retried = api.get(path + "/deliveries/" + delivery).json();
    assertThat(retried.get("state").asString()).isEqualTo("PENDING");
    assertThat(retried.get("attempts").asInt()).isEqualTo(2);
    assertThat(retried.get("attempt_log")).hasSize(2);

    // A pending delivery isn't replayed: it will be sent anyway.
    Api.Response again = api.post(path + "/replay", "{\"event_id\":\"" + event + "\"}");
    assertThat(again.json().get("replayed").asInt()).isZero();
    assertThat(again.json().get("already_pending").asInt()).isOne();

    statusByPath.get("/hook").set(200);
    jdbc.sql("UPDATE webhook_deliveries SET next_attempt_at = now()").update();
    sendAllDue();
    JsonNode done = api.get(path + "/deliveries/" + delivery).json();
    assertThat(done.get("state").asString()).isEqualTo("SUCCEEDED");
    assertThat(done.get("attempts").asInt()).isEqualTo(3);
    Map<String, String> last = received.getLast();
    assertThat(last.get("payflow-delivery-attempt")).isEqualTo("3");
    assertThat(last.get("payflow-event-id")).isEqualTo(event.toString());

    // Replaying a delivered event sends it once more, the same bytes as before.
    assertThat(api.post(path + "/replay", "{\"event_id\":\"" + event + "\"}").json())
        .isEqualTo(json.readTree("{\"replayed\":1,\"already_pending\":0}"));
    sendAllDue();
    assertThat(received.getLast().get(":body")).isEqualTo(received.getFirst().get(":body"));
    assertThat(received.getLast().get("payflow-delivery-attempt")).isEqualTo("4");
  }

  @Test
  void aRangeReplaySendsTheSettledDeliveriesInItAndLeavesOthersAlone() {
    String[] hook = endpoint(api, "/hook");
    String[] other = endpoint(api, "/other");
    Instant t0 = micros(Instant.now().minus(Duration.ofHours(2)));
    List<UUID> events = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      events.add(event("acme", UUID.randomUUID(), 1, "order.created", t0.plusSeconds(i)));
    }
    // 0, 1 and 2 fail and are given up; 3 and 4 succeed; 5 stays pending.
    jdbc.sql(
            """
            UPDATE webhook_deliveries SET state = CASE
                WHEN event_id = ANY (:failed) THEN 'FAILED' ELSE 'SUCCEEDED' END,
              next_attempt_at = NULL
            WHERE event_id <> :pending AND endpoint_id = :e
            """)
        .param("e", UUID.fromString(hook[0]))
        .param("failed", events.subList(0, 3).toArray(UUID[]::new))
        .param("pending", events.get(5))
        .update();
    String path = HOOKS + "/" + hook[0] + "/replay";

    Api.Response failedOnly =
        api.post(
            path,
            "{\"created_gte\":\"%s\",\"created_lt\":\"%s\",\"state\":[\"FAILED\"]}"
                .formatted(t0.plusSeconds(1), t0.plusSeconds(6)));
    assertThat(failedOnly.status()).as(failedOnly.body()).isEqualTo(200);
    assertThat(failedOnly.json().get("replayed").asInt()).as("events 1 and 2").isEqualTo(2);
    assertThat(failedOnly.json().get("already_pending").asInt()).isZero();

    Api.Response all = api.post(path, "{\"created_gte\":\"%s\"}".formatted(t0));
    assertThat(all.json().get("replayed").asInt()).as("0, 3 and 4").isEqualTo(3);
    assertThat(all.json().get("already_pending").asInt()).as("1, 2 and 5").isEqualTo(3);
    assertThat(
            jdbc.sql(
                    "SELECT count(*) FROM webhook_deliveries WHERE endpoint_id = :e"
                        + " AND state = 'PENDING' AND replayed_at IS NULL")
                .param("e", UUID.fromString(other[0]))
                .query(Integer.class)
                .single())
        .as("the other endpoint's deliveries are untouched")
        .isEqualTo(6);
    assertThat(
            jdbc.sql(
                    "SELECT count(*) FROM webhook_deliveries WHERE endpoint_id = :e"
                        + " AND replayed_at IS NOT NULL")
                .param("e", UUID.fromString(other[0]))
                .query(Integer.class)
                .single())
        .isZero();
  }

  @Test
  void aReplayOfMoreThanOneThousandDeliveriesIsRefusedAndChangesNothing() {
    String[] hook = endpoint(api, "/hook");
    UUID endpoint = UUID.fromString(hook[0]);
    jdbc.sql(
            """
            INSERT INTO webhook_deliveries (id, endpoint_id, event_id, event_type, order_id,
                order_seq, payload, state, attempts, next_attempt_at, created_at, updated_at)
            SELECT gen_random_uuid(), :e, gen_random_uuid(), 'order.created', gen_random_uuid(),
                1, '{}', 'FAILED', 1, NULL, now() - make_interval(secs => g), now()
            FROM generate_series(1, 1001) g
            """)
        .param("e", endpoint)
        .update();
    String path = HOOKS + "/" + hook[0] + "/replay";
    String since = Instant.now().minus(Duration.ofHours(1)).toString();
    Api.Response tooMany = api.post(path, "{\"created_gte\":\"" + since + "\"}");
    assertThat(tooMany.status()).as(tooMany.body()).isEqualTo(400);
    assertThat(tooMany.errorCode()).isEqualTo("invalid_argument");
    assertThat(tooMany.json().toString()).contains("more than 1000");
    assertThat(
            jdbc.sql("SELECT count(*) FROM webhook_deliveries WHERE state = 'FAILED'")
                .query(Integer.class)
                .single())
        .isEqualTo(1001);

    // Narrowed to exactly 1,000, it goes through.
    String upTo =
        jdbc.sql("SELECT max(created_at) FROM webhook_deliveries WHERE endpoint_id = :e")
            .param("e", endpoint)
            .query(java.time.OffsetDateTime.class)
            .single()
            .toInstant()
            .toString();
    Api.Response ok =
        api.post(path, "{\"created_gte\":\"" + since + "\",\"created_lt\":\"" + upTo + "\"}");
    assertThat(ok.status()).as(ok.body()).isEqualTo(200);
    assertThat(ok.json().get("replayed").asInt()).isEqualTo(1000);
  }

  @Test
  void replayRequestsAreValidated() {
    String[] hook = endpoint(api, "/hook");
    String path = HOOKS + "/" + hook[0] + "/replay";
    String now = Instant.now().toString();
    for (String body :
        List.of(
            "{}",
            "{\"state\":[\"FAILED\"]}",
            "{\"created_lt\":\"" + now + "\"}",
            "{\"event_id\":\"" + UUID.randomUUID() + "\",\"created_gte\":\"" + now + "\"}",
            "{\"created_gte\":\"" + now + "\",\"state\":[\"PENDING\"]}",
            "{\"created_gte\":\"" + now + "\",\"state\":[\"DONE\"]}",
            "{\"created_gte\":\"yesterday\"}",
            "{\"created_gte\":\"" + now + "\",\"created_lt\":\"" + now + "\"}",
            "{\"event_id\":\"nope\"}",
            "{\"event_id\":\"" + UUID.randomUUID() + "\",\"extra\":1}")) {
      Api.Response r = api.post(path, body);
      assertThat(r.status()).as(body + " " + r.body()).isEqualTo(400);
      assertThat(r.errorCode()).isEqualTo("invalid_argument");
    }
    Api.Response unknown = api.post(path, "{\"event_id\":\"" + UUID.randomUUID() + "\"}");
    assertThat(unknown.status()).isEqualTo(404);
    assertThat(unknown.errorCode()).isEqualTo("not_found");
    assertThat(unknown.json().toString()).contains("has no delivery to this endpoint");
    // A range with nothing in it is not an error.
    Api.Response empty = api.post(path, "{\"created_gte\":\"" + now + "\"}");
    assertThat(empty.json()).isEqualTo(json.readTree("{\"replayed\":0,\"already_pending\":0}"));
  }

  @Test
  void aPingIsSignedSentOnceAndLoggedWhateverTheFilterSays() throws Exception {
    Api.Response created =
        api.post(
            HOOKS, "{\"url\":\"" + url("/ping") + "\",\"enabled_events\":[\"refund.failed\"]}");
    String id = created.json().get("id").asString();
    String secret = created.json().get("secret").asString();

    Api.Response ping = api.post(HOOKS + "/" + id + "/ping", null);
    assertThat(ping.status()).as(ping.body()).isEqualTo(202);
    JsonNode p = ping.json();
    String delivery = p.get("id").asString();
    assertThat(ping.header("Location")).isEqualTo(HOOKS + "/" + id + "/deliveries/" + delivery);
    assertThat(p.get("event_type").asString()).isEqualTo("ping");
    assertThat(p.get("state").asString()).isEqualTo("PENDING");
    assertThat(p.has("order_id")).isFalse();
    assertThat(p.has("last_attempt")).isFalse();

    sendAllDue();
    assertThat(received).hasSize(1);
    Map<String, String> r = received.getFirst();
    byte[] body = r.get(":body").getBytes(StandardCharsets.UTF_8);
    assertThat(
            WebhookSignature.verify(
                r.get("payflow-signature"),
                body,
                secret,
                Instant.now().getEpochSecond(),
                WebhookSignature.DEFAULT_TOLERANCE))
        .isTrue();
    assertThat(r.get("payflow-event-type")).isEqualTo("ping");
    JsonNode payload = json.readTree(body);
    assertThat(payload.get("event_type").asString()).isEqualTo("ping");
    assertThat(payload.get("endpoint_id").asString()).isEqualTo(id);
    assertThat(payload.get("merchant_id").asString()).isEqualTo("acme");
    assertThat(payload.get("event_id").asString()).isEqualTo(p.get("event_id").asString());

    JsonNode logged = api.get(ping.header("Location")).json();
    assertThat(logged.get("state").asString()).isEqualTo("SUCCEEDED");
    assertThat(logged.get("last_attempt").get("response_status").asInt()).isEqualTo(200);
    assertThat(logged.get("payload")).isEqualTo(payload);

    // A failed ping is tried once, not retried for days.
    statusByPath.put("/ping", new AtomicInteger(404));
    String failed = api.post(HOOKS + "/" + id + "/ping", null).json().get("id").asString();
    sendAllDue();
    JsonNode f = api.get(HOOKS + "/" + id + "/deliveries/" + failed).json();
    assertThat(f.get("state").asString()).isEqualTo("FAILED");
    assertThat(f.get("attempts").asInt()).isOne();
    assertThat(f.get("last_attempt").get("response_status").asInt()).isEqualTo(404);
    assertThat(received).hasSize(2);

    // It can be replayed like any delivery, and is still tried once.
    api.post(
        HOOKS + "/" + id + "/replay", "{\"event_id\":\"" + f.get("event_id").asString() + "\"}");
    statusByPath.get("/ping").set(200);
    sendAllDue();
    assertThat(api.get(HOOKS + "/" + id + "/deliveries/" + failed).json().get("state").asString())
        .isEqualTo("SUCCEEDED");

    // A disabled endpoint receives nothing, pings included.
    api.post(HOOKS + "/" + id, "{\"state\":\"DISABLED\"}");
    Api.Response off = api.post(HOOKS + "/" + id + "/ping", null);
    assertThat(off.status()).isEqualTo(400);
    assertThat(off.errorCode()).isEqualTo("invalid_argument");
    assertThat(off.json().toString()).contains("disabled");

    // The same key replays the first answer and queues nothing more.
    api.post(HOOKS + "/" + id, "{\"state\":\"ENABLED\"}");
    Api.Response first = api.post(HOOKS + "/" + id + "/ping", null, "Idempotency-Key", "ping-1");
    Api.Response again = api.post(HOOKS + "/" + id + "/ping", null, "Idempotency-Key", "ping-1");
    assertThat(again.header("Idempotent-Replayed")).isEqualTo("true");
    assertThat(again.json()).isEqualTo(first.json());
    Set<String> pings =
        new HashSet<>(
            jdbc.sql("SELECT id::text FROM webhook_deliveries WHERE event_type = 'ping'")
                .query(String.class)
                .list());
    assertThat(pings).hasSize(3);
  }
}
