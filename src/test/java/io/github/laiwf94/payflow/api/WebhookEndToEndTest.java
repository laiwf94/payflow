package io.github.laiwf94.payflow.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.laiwf94.payflow.support.Api;
import io.github.laiwf94.payflow.support.Db;
import io.github.laiwf94.payflow.support.TestKeys;
import io.github.laiwf94.payflow.webhook.WebhookSignature;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Webhooks end to end: payflow with its saga, outbox relay and dispatcher running, and a stand-in
 * merchant in its own container that checks every signature with the Python example from
 * docs/webhooks.md. Two orders go through their whole lives (one completed and refunded in two
 * steps, one declined), the merchant's first three answers are 500s, and the merchant rotates its
 * signing secret between the order's capture and its refunds, deploying the new secret only after
 * payflow has started signing with it. The merchant still gets every event exactly once, each
 * order's events in {@code seq} order, and never refuses a signature.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "payflow.saga.enabled=true",
      "payflow.outbox.enabled=true",
      "payflow.webhooks.enabled=true",
      "payflow.webhooks.poll-interval=100ms",
      "payflow.webhooks.retry-base=200ms",
      "payflow.webhooks.retry-cap=1s"
    })
@DirtiesContext
class WebhookEndToEndTest {

  private static final String HOOKS = "/v1/webhook_endpoints";
  private static final Duration SETTLE = Duration.ofSeconds(60);
  private static final Pattern PYTHON_EXAMPLE =
      Pattern.compile("<!-- example: python -->\\n```python\\n(.*?)\\n```", Pattern.DOTALL);

  @SuppressWarnings("resource") // stopped in stopMerchant
  private static final GenericContainer<?> MERCHANT =
      new GenericContainer<>(DockerImageName.parse("python:3.13.17-alpine3.24"))
          .withCopyToContainer(Transferable.of(guidePython()), "/merchant/payflow_webhooks.py")
          .withCopyToContainer(Transferable.of(resource("merchant.py")), "/merchant/merchant.py")
          .withWorkingDirectory("/merchant")
          .withCommand("python3", "-s", "-E", "merchant.py")
          .withExposedPorts(8080)
          .waitingFor(Wait.forHttp("/control/log").forStatusCode(200))
          .withStartupTimeout(Duration.ofSeconds(60));

  private static String merchantHost;

  @DynamicPropertySource
  static void merchant(DynamicPropertyRegistry registry) {
    MERCHANT.start();
    merchantHost = ipv4(MERCHANT.getHost());
    // The merchant is reached through the container's published port on the Docker host, which
    // is not a public address: let exactly that address through, as a development stack would.
    registry.add("payflow.webhooks.allowed-networks", () -> merchantHost + "/32");
  }

  @AfterAll
  static void stopMerchant() {
    MERCHANT.stop();
  }

  @LocalServerPort int port;
  @Autowired JdbcClient jdbc;
  @Autowired ObjectMapper json;

  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private Api api;

  @BeforeEach
  void setUp() {
    Db.truncateAll(jdbc);
    api = new Api(port, TestKeys.ACME);
  }

  // ---------------------------------------------------------------------------------------------

  /** One request the merchant was sent, as it logged it. */
  record Received(
      UUID eventId,
      String eventType,
      UUID deliveryId,
      int attempt,
      String signature,
      String bodyBase64,
      int secretsHeld,
      int status,
      String outcome) {

    byte[] body() {
      return Base64.getDecoder().decode(bodyBase64);
    }
  }

  /** One event payflow wrote for the merchant, as the outbox holds it. */
  record Written(UUID eventId, UUID orderId, long seq, String type, String payload) {}

  private static String guidePython() {
    try {
      Matcher m =
          PYTHON_EXAMPLE.matcher(
              Files.readString(Path.of("docs", "webhooks.md"), StandardCharsets.UTF_8));
      assertThat(m.find()).as("docs/webhooks.md has its Python example").isTrue();
      return m.group(1);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String resource(String name) {
    try (var in = WebhookEndToEndTest.class.getResourceAsStream("/webhook-merchant/" + name)) {
      assertThat(in).as(name).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String ipv4(String host) {
    try {
      return Arrays.stream(InetAddress.getAllByName(host))
          .filter(a -> a instanceof Inet4Address)
          .findFirst()
          .orElseThrow(() -> new IllegalStateException(host + " has no IPv4 address"))
          .getHostAddress();
    } catch (UnknownHostException e) {
      throw new IllegalStateException(e);
    }
  }

  private String merchantUrl(String path) {
    return "http://" + merchantHost + ":" + MERCHANT.getMappedPort(8080) + path;
  }

  private String control(String method, String path, String body) {
    HttpRequest.Builder b =
        HttpRequest.newBuilder(URI.create(merchantUrl(path))).timeout(Duration.ofSeconds(10));
    b.method(
        method,
        body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body));
    try {
      HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
      assertThat(r.statusCode()).as("%s %s: %s", method, path, r.body()).isEqualTo(200);
      return r.body();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  /** Tells the merchant which secrets it holds from now on: a deploy on its side. */
  private void merchantHolds(String... secrets) {
    control("POST", "/control/secrets", json.writeValueAsString(Map.of("secrets", secrets)));
  }

  private List<Received> received() {
    List<Received> out = new ArrayList<>();
    for (JsonNode r : json.readTree(control("GET", "/control/log", null)).get("requests")) {
      out.add(
          new Received(
              UUID.fromString(r.get("event_id").asString()),
              r.get("event_type").asString(),
              UUID.fromString(r.get("delivery_id").asString()),
              r.get("attempt").asInt(),
              r.get("signature").asString(),
              r.get("body").asString(),
              r.get("secrets_held").asInt(),
              r.get("status").asInt(),
              r.get("outcome").asString()));
    }
    return out;
  }

  /** Every event payflow has written for acme, oldest first within each order. */
  private List<Written> written() {
    return jdbc.sql(
            """
            SELECT event_id, aggregate_id, aggregate_seq, event_type, payload::text AS payload
            FROM outbox WHERE payload->>'merchant_id' = 'acme'
            ORDER BY aggregate_id, aggregate_seq
            """)
        .query(
            (rs, i) ->
                new Written(
                    rs.getObject("event_id", UUID.class),
                    rs.getObject("aggregate_id", UUID.class),
                    rs.getLong("aggregate_seq"),
                    rs.getString("event_type"),
                    rs.getString("payload")))
        .list();
  }

  /** Waits until the merchant has accepted every event written so far; returns what it got. */
  private List<Received> awaitEveryEventAccepted() {
    await()
        .atMost(SETTLE)
        .pollInterval(Duration.ofMillis(200))
        .until(
            () -> {
              Set<UUID> accepted =
                  received().stream()
                      .filter(r -> r.outcome().equals("accepted"))
                      .map(Received::eventId)
                      .collect(Collectors.toSet());
              return accepted.containsAll(written().stream().map(Written::eventId).toList());
            });
    return received();
  }

  private JsonNode awaitOrder(String id, String state) {
    await()
        .atMost(SETTLE)
        .until(() -> api.get("/v1/orders/" + id).json().get("state").asString().equals(state));
    return api.get("/v1/orders/" + id).json();
  }

  private void awaitRefundsSettled(String id) {
    await()
        .atMost(SETTLE)
        .until(
            () -> {
              for (JsonNode r : api.get("/v1/orders/" + id + "/refunds").json().get("data")) {
                if (r.get("state").asString().equals("PENDING")) {
                  return false;
                }
              }
              return true;
            });
  }

  private static long timestamp(String signature) {
    for (String part : signature.split(",", -1)) {
      if (part.startsWith("t=")) {
        return Long.parseLong(part.substring(2));
      }
    }
    throw new AssertionError("no timestamp in " + signature);
  }

  private static long v1Count(String signature) {
    return Arrays.stream(signature.split(",", -1)).filter(p -> p.startsWith("v1=")).count();
  }

  /** Whether {@code r}'s signature verifies under {@code secret} alone, when it was sent. */
  private static boolean signedWith(Received r, String secret) {
    return WebhookSignature.verify(
        r.signature(),
        r.body(),
        secret,
        timestamp(r.signature()),
        WebhookSignature.DEFAULT_TOLERANCE);
  }

  private static List<Received> since(List<Received> all, int from, Predicate<Received> p) {
    return all.subList(from, all.size()).stream().filter(p).toList();
  }

  // ---------------------------------------------------------------------------------------------

  @Test
  void aMerchantThatFailsThreeTimesAndRotatesItsSecretStillGetsEveryEventInOrder() {
    Api.Response created =
        api.post(
            HOOKS,
            "{\"url\":\"" + merchantUrl("/hooks") + "\",\"enabled_events\":[\"*\"]}",
            "Idempotency-Key",
            "e2e-endpoint");
    assertThat(created.status()).as(created.body()).isEqualTo(201);
    String endpoint = created.json().get("id").asString();
    String oldSecret = created.json().get("secret").asString();
    merchantHolds(oldSecret);
    control("POST", "/control/fail", "{\"count\":3}");

    // Phase 1, signed with the old secret only: a card order through to COMPLETED and a declined
    // one through to FAILED. The merchant's first three answers are 500s.
    Api.Response a = api.post("/v1/orders", Api.orderBody("ord-a"));
    Api.Response b = api.post("/v1/orders", Api.orderBody("pm_card_declined", "12345"));
    assertThat(a.status()).as(a.body()).isEqualTo(201);
    assertThat(b.status()).as(b.body()).isEqualTo(201);
    String orderA = a.json().get("id").asString();
    String orderB = b.json().get("id").asString();
    awaitOrder(orderA, "COMPLETED");
    awaitOrder(orderB, "FAILED");
    List<Received> phase1 = awaitEveryEventAccepted();
    int rotatedAt = phase1.size();
    assertThat(phase1).filteredOn(r -> r.status() == 500).hasSize(3);
    assertThat(phase1)
        .as("one signature, the old secret's")
        .allSatisfy(
            r -> {
              assertThat(v1Count(r.signature())).isOne();
              assertThat(signedWith(r, oldSecret)).isTrue();
            });

    // The merchant rotates. payflow signs with both secrets from now on, while the merchant's
    // receivers still hold only the old one.
    Api.Response rotated =
        api.post(
            HOOKS + "/" + endpoint + "/rotate_secret",
            "{\"previous_secret_expires_in\":3600}",
            "Idempotency-Key",
            "e2e-rotate");
    assertThat(rotated.status()).as(rotated.body()).isEqualTo(200);
    String newSecret = rotated.json().get("secret").asString();
    assertThat(newSecret).isNotEqualTo(oldSecret);

    // Phase 2, before the merchant deploys the new secret: a partial refund.
    assertThat(api.post("/v1/orders/" + orderA + "/refunds", "{\"amount\":300}").status())
        .isEqualTo(202);
    awaitOrder(orderA, "PARTIALLY_REFUNDED");
    awaitRefundsSettled(orderA);
    List<Received> phase2All = awaitEveryEventAccepted();
    int deployedAt = phase2All.size();
    List<Received> phase2 = since(phase2All, rotatedAt, r -> true);
    assertThat(phase2).isNotEmpty();
    assertThat(phase2)
        .as("signed with the new secret and the old, accepted on the old one alone")
        .allSatisfy(
            r -> {
              assertThat(r.secretsHeld()).isOne();
              assertThat(v1Count(r.signature())).isEqualTo(2);
              assertThat(signedWith(r, newSecret)).isTrue();
              assertThat(signedWith(r, oldSecret)).isTrue();
              assertThat(r.outcome()).isEqualTo("accepted");
            });

    // Phase 3: the merchant deploys the new secret and drops the old one; the rest is refunded.
    merchantHolds(newSecret);
    assertThat(api.post("/v1/orders/" + orderA + "/refunds", "{\"amount\":2200}").status())
        .isEqualTo(202);
    awaitOrder(orderA, "REFUNDED");
    awaitRefundsSettled(orderA);
    List<Received> all = awaitEveryEventAccepted();
    List<Received> phase3 = since(all, deployedAt, r -> true);
    assertThat(phase3).isNotEmpty();
    assertThat(phase3)
        .as("accepted on the new secret alone")
        .allSatisfy(
            r -> {
              assertThat(signedWith(r, newSecret)).isTrue();
              assertThat(r.outcome()).isEqualTo("accepted");
            });

    // Every event arrived once, with the bytes payflow stored, and no signature was refused.
    List<Written> events = written();
    assertThat(all).noneMatch(r -> r.outcome().equals("bad_signature"));
    assertThat(all).noneMatch(r -> r.outcome().equals("duplicate"));
    Map<UUID, Received> accepted = new LinkedHashMap<>();
    for (Received r : all) {
      if (r.outcome().equals("accepted")) {
        assertThat(accepted.put(r.eventId(), r)).as("accepted once: %s", r.eventId()).isNull();
      }
    }
    assertThat(accepted.keySet())
        .containsExactlyInAnyOrderElementsOf(events.stream().map(Written::eventId).toList());
    for (Written w : events) {
      Received r = accepted.get(w.eventId());
      assertThat(r.eventType()).isEqualTo(w.type());
      assertThat(new String(r.body(), StandardCharsets.UTF_8)).isEqualTo(w.payload());
      assertThat(json.readTree(r.body()).get("seq").asLong()).isEqualTo(w.seq());
    }

    // Each order's events arrived in seq order: nothing of an order was sent before every earlier
    // event of that order had been accepted, failures and their retries included.
    Map<UUID, Written> byEvent = new HashMap<>();
    events.forEach(w -> byEvent.put(w.eventId(), w));
    Map<UUID, Long> lastAccepted = new HashMap<>();
    for (Received r : all) {
      Written w = byEvent.get(r.eventId());
      long done = lastAccepted.getOrDefault(w.orderId(), -1L);
      assertThat(w.seq())
          .as("%s seq %d sent after seq %d was accepted", w.orderId(), w.seq(), done)
          .isEqualTo(done + 1);
      if (r.outcome().equals("accepted")) {
        lastAccepted.put(w.orderId(), w.seq());
      }
    }
    Map<UUID, List<String>> types = new LinkedHashMap<>();
    for (Written w : events) {
      types.computeIfAbsent(w.orderId(), o -> new ArrayList<>()).add(w.type());
    }
    assertThat(types.get(UUID.fromString(orderA)))
        .startsWith("order.created")
        .contains("order.completed", "order.partially_refunded", "order.refunded")
        .contains("refund.created", "refund.succeeded");
    assertThat(types.get(UUID.fromString(orderB)))
        .startsWith("order.created")
        .endsWith("order.failed");

    // The three failures were retried as later attempts of the same deliveries.
    List<Received> failed = all.stream().filter(r -> r.status() == 500).toList();
    assertThat(failed).hasSize(3);
    for (Received f : failed) {
      Received ok = accepted.get(f.eventId());
      assertThat(ok.deliveryId()).isEqualTo(f.deliveryId());
      assertThat(ok.attempt()).isGreaterThan(f.attempt());
    }

    // payflow's delivery log tells the same story, and the endpoint came through healthy.
    List<JsonNode> log = new ArrayList<>();
    String cursor = null;
    do {
      Api.Response page =
          api.get(
              HOOKS
                  + "/"
                  + endpoint
                  + "/deliveries?limit=100"
                  + (cursor == null ? "" : "&cursor=" + cursor));
      assertThat(page.status()).as(page.body()).isEqualTo(200);
      page.json().get("data").forEach(log::add);
      cursor = page.json().has("next_cursor") ? page.json().get("next_cursor").asString() : null;
    } while (cursor != null);
    assertThat(log).hasSize(events.size());
    assertThat(log).allSatisfy(d -> assertThat(d.get("state").asString()).isEqualTo("SUCCEEDED"));
    assertThat(log.stream().mapToInt(d -> d.get("attempts").asInt()).sum())
        .as("one attempt per request the merchant was sent")
        .isEqualTo(all.size());
    Set<UUID> retried =
        log.stream()
            .filter(d -> d.get("attempts").asInt() > 1)
            .map(d -> UUID.fromString(d.get("event_id").asString()))
            .collect(Collectors.toCollection(HashSet::new));
    assertThat(retried)
        .isEqualTo(failed.stream().map(Received::eventId).collect(Collectors.toSet()));
    JsonNode hook = api.get(HOOKS + "/" + endpoint).json();
    assertThat(hook.get("state").asString()).isEqualTo("ENABLED");
    assertThat(hook.has("failing_since")).isFalse();
    assertThat(hook.has("previous_secret_expires_at")).as("old secret still signing").isTrue();
  }
}
