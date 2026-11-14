package io.github.laiwf94.payflow.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.github.laiwf94.payflow.order.OrderTransitions;
import io.github.laiwf94.payflow.order.Orders;
import io.github.laiwf94.payflow.outbox.OutboxRelay;
import io.github.laiwf94.payflow.saga.Backoff;
import io.github.laiwf94.payflow.security.SecretBox;
import io.github.laiwf94.payflow.support.Db;
import io.github.laiwf94.payflow.support.MutableClock;
import io.github.laiwf94.payflow.webhook.WebhookDeliveryRepository.Attempt;
import io.github.laiwf94.payflow.webhook.WebhookDeliveryRepository.Claim;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** The dispatcher against real Postgres and a real HTTP server on loopback. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class WebhookDispatcherTest {

  @Autowired JdbcClient jdbc;
  @Autowired TransactionTemplate tx;
  @Autowired WebhookDeliveryRepository deliveries;
  @Autowired WebhookEndpointRepository endpointRepo;
  @Autowired WebhookEndpointService endpointService;
  @Autowired SecretBox box;
  @Autowired OutboxRelay relay;
  @Autowired OrderTransitions transitions;
  @Autowired ObjectMapper json;

  private final MutableClock clock = new MutableClock(Instant.now());
  private final Map<UUID, String> secrets = new HashMap<>();
  private final List<WebhookDispatcher> started = new ArrayList<>();
  private Merchant merchant;

  /** What the stand-in merchant server got. */
  record Received(String path, Map<String, String> headers, String body, long atNanos) {
    String header(String name) {
      return headers.get(name.toLowerCase(Locale.ROOT));
    }
  }

  /** How it answers: a status after a delay. */
  record Reply(int status, long delayMillis) {
    static Reply of(int status) {
      return new Reply(status, 0);
    }
  }

  /** A stand-in merchant server: records every request and answers as told. */
  static final class Merchant implements AutoCloseable {
    final HttpServer server;
    final List<Received> received = new CopyOnWriteArrayList<>();
    final AtomicInteger inFlight = new AtomicInteger();
    final Map<String, AtomicInteger> maxInFlight = new ConcurrentHashMap<>();
    final Map<String, AtomicInteger> currentByPath = new ConcurrentHashMap<>();
    volatile Function<Received, Reply> answer = r -> Reply.of(200);
    private final AtomicBoolean closed = new AtomicBoolean();

    Merchant() throws Exception {
      server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
      server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
      server.createContext(
          "/",
          ex -> {
            String path = ex.getRequestURI().getPath();
            AtomicInteger now = currentByPath.computeIfAbsent(path, p -> new AtomicInteger());
            int c = now.incrementAndGet();
            maxInFlight
                .computeIfAbsent(path, p -> new AtomicInteger())
                .accumulateAndGet(c, Math::max);
            try (InputStream in = ex.getRequestBody()) {
              Map<String, String> h = new HashMap<>();
              ex.getRequestHeaders()
                  .forEach((k, v) -> h.put(k.toLowerCase(Locale.ROOT), String.join(",", v)));
              Received r =
                  new Received(
                      path,
                      h,
                      new String(in.readAllBytes(), StandardCharsets.UTF_8),
                      System.nanoTime());
              received.add(r);
              Reply reply = answer.apply(r);
              if (reply.delayMillis() > 0) {
                Thread.sleep(reply.delayMillis());
              }
              byte[] body = ("answer " + reply.status()).getBytes(StandardCharsets.UTF_8);
              ex.sendResponseHeaders(reply.status(), body.length);
              try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
              }
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            } finally {
              now.decrementAndGet();
              ex.close();
            }
          });
      server.start();
    }

    String url(String path) {
      return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    @Override
    public void close() {
      if (closed.compareAndSet(false, true)) {
        server.stop(0);
      }
    }
  }

  @BeforeEach
  void setUp() throws Exception {
    Db.truncateAll(jdbc);
    merchant = new Merchant();
  }

  @AfterEach
  void tearDown() {
    started.forEach(WebhookDispatcher::stop);
    merchant.close();
  }

  private static WebhookProperties props(
      Duration timeout, int concurrency, int perEndpoint, Duration window, Duration grace) {
    return new WebhookProperties(
        List.of("127.0.0.1/32"),
        Duration.ofSeconds(1),
        timeout,
        1024,
        true,
        Duration.ofMillis(20),
        16,
        concurrency,
        perEndpoint,
        timeout.plusSeconds(5),
        Duration.ofSeconds(5),
        Duration.ofMinutes(10),
        window,
        grace,
        Duration.ofDays(3));
  }

  private static WebhookProperties props() {
    return props(Duration.ofSeconds(3), 8, 8, Duration.ofDays(3), Duration.ofSeconds(1));
  }

  private WebhookDispatcher dispatcher(WebhookProperties p, Clock c, AddressPolicy policy)
      throws Exception {
    SafeHttpSender sender =
        new SafeHttpSender(
            policy,
            SafeHttpSender.Resolver.SYSTEM,
            SSLContext.getDefault(),
            new SafeHttpSender.Options(p.connectTimeout(), p.timeout(), p.maxResponseBody()),
            "payflow-webhooks/test");
    return new WebhookDispatcher(
        deliveries,
        endpointService,
        sender,
        p,
        c,
        new Backoff(p.retryBase(), p.retryCap(), new SplittableRandom(7)),
        new SimpleMeterRegistry());
  }

  private WebhookDispatcher dispatcher(WebhookProperties p) throws Exception {
    return dispatcher(p, clock, AddressPolicy.allowing(p.allowedNetworks()));
  }

  private WebhookDispatcher running(WebhookProperties p) throws Exception {
    WebhookDispatcher d =
        dispatcher(p, Clock.systemUTC(), AddressPolicy.allowing(p.allowedNetworks()));
    d.start();
    started.add(d);
    return d;
  }

  private UUID endpoint(String merchantId, String url, List<String> events) {
    Instant now = clock.instant();
    WebhookEndpoint e =
        WebhookEndpoint.created(UUID.randomUUID(), merchantId, url, null, events, now);
    String secret = "whsec_test_" + UUID.randomUUID();
    endpointRepo.insert(
        e, box.seal(secret, WebhookEndpointRepository.secretContext(merchantId, e.id())));
    secrets.put(e.id(), secret);
    return e.id();
  }

  private UUID endpoint(String url) {
    return endpoint("acme", url, List.of("*"));
  }

  /** An outbox event for {@code order}, as the writer would store it; returns its outbox id. */
  private long event(UUID order, long seq, String type, String merchantId) {
    UUID eventId = UUID.randomUUID();
    String payload =
        json.writeValueAsString(
            Map.of(
                "event_id", eventId,
                "event_type", type,
                "order_id", order,
                "merchant_id", merchantId,
                "seq", seq));
    return jdbc.sql(
            """
            INSERT INTO outbox (event_id, aggregate_id, aggregate_seq, event_type, payload,
                correlation_id, created_at)
            VALUES (:e, :o, :s, :t, CAST(:p AS jsonb), 'corr', :now) RETURNING id
            """)
        .param("e", eventId)
        .param("o", order)
        .param("s", seq)
        .param("t", type)
        .param("p", payload)
        .param("now", clock.instant().atOffset(ZoneOffset.UTC))
        .query(Long.class)
        .single();
  }

  /** Writes events and creates their deliveries, as a relay batch would. */
  private void relayed(Long... ids) {
    tx.executeWithoutResult(s -> deliveries.enqueue(List.of(ids), clock.instant()));
  }

  private String state(UUID delivery) {
    return jdbc.sql("SELECT state FROM webhook_deliveries WHERE id = :id")
        .param("id", delivery)
        .query(String.class)
        .single();
  }

  private Instant nextAttempt(UUID delivery) {
    OffsetDateTime t =
        jdbc.sql("SELECT next_attempt_at FROM webhook_deliveries WHERE id = :id")
            .param("id", delivery)
            .query(OffsetDateTime.class)
            .optional()
            .orElse(null);
    return t == null ? null : t.toInstant();
  }

  private UUID onlyDelivery() {
    return jdbc.sql("SELECT id FROM webhook_deliveries").query(UUID.class).single();
  }

  private WebhookEndpoint stored(UUID endpoint) {
    return endpointRepo.find("acme", endpoint).orElseThrow();
  }

  /** Runs the dispatcher until nothing is due, moving the clock to each next attempt. */
  private int drive(WebhookDispatcher d, Instant until) {
    int attempts = 0;
    while (true) {
      int n = d.runOnce();
      attempts += n;
      if (n > 0) {
        continue;
      }
      OffsetDateTime next =
          jdbc.sql(
                  """
                  SELECT min(d.next_attempt_at) FROM webhook_deliveries d
                  JOIN webhook_endpoints e ON e.id = d.endpoint_id
                  WHERE d.state = 'PENDING' AND e.state = 'ENABLED'
                  """)
              .query(OffsetDateTime.class)
              .optional()
              .orElse(null);
      if (next == null || next.toInstant().isAfter(until)) {
        return attempts;
      }
      clock.advance(Duration.between(clock.instant(), next.toInstant()));
    }
  }

  private List<Map<String, Object>> attempts(UUID delivery) {
    return jdbc.sql(
            """
            SELECT attempted_at, latency_ms, response_status, response_body, error
            FROM webhook_attempts WHERE delivery_id = :id ORDER BY id
            """)
        .param("id", delivery)
        .query()
        .listOfRows();
  }

  private long seq(Received r) {
    JsonNode n = json.readTree(r.body());
    return n.get("seq").asLong();
  }

  private static void await(BooleanSupplier cond, Duration max) throws InterruptedException {
    long deadline = System.nanoTime() + max.toNanos();
    while (!cond.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("condition not met within " + max);
      }
      Thread.sleep(10);
    }
  }

  @Test
  void theRelayHandsEachEventToEveryEnabledEndpointThatWantsIt() {
    UUID all = endpoint("acme", merchant.url("/all"), List.of("*"));
    UUID created = endpoint("acme", merchant.url("/created"), List.of("order.created"));
    UUID cancels = endpoint("acme", merchant.url("/cancel"), List.of("order.cancel_requested"));
    UUID other = endpoint("globex", merchant.url("/globex"), List.of("*"));
    UUID off = endpoint("acme", merchant.url("/off"), List.of("*"));
    jdbc.sql("UPDATE webhook_endpoints SET state = 'DISABLED' WHERE id = :id")
        .param("id", off)
        .update();
    UUID gone = endpoint("acme", merchant.url("/gone"), List.of("*"));
    endpointService.delete("acme", gone);

    transitions.create(Orders.pending("acme", clock.instant()));
    transitions.create(Orders.pending("acme", clock.instant()));
    assertThat(relay.relayOnce()).isEqualTo(2);

    Map<UUID, Integer> perEndpoint = new HashMap<>();
    jdbc.sql("SELECT endpoint_id, count(*)::int AS n FROM webhook_deliveries GROUP BY endpoint_id")
        .query((rs, i) -> perEndpoint.put(rs.getObject(1, UUID.class), rs.getInt(2)))
        .list();
    assertThat(perEndpoint).containsOnly(Map.entry(all, 2), Map.entry(created, 2));
    assertThat(perEndpoint).doesNotContainKeys(cancels, other, off, gone);

    // Each delivery carries the event exactly as the outbox wrote it, and is due now.
    List<Map<String, Object>> rows =
        jdbc.sql(
                """
                SELECT d.state, d.attempts, d.next_attempt_at = d.created_at AS due_now,
                       d.payload = o.payload AS same_payload, d.order_seq = o.aggregate_seq AS seq,
                       d.order_id = o.aggregate_id AS same_order, d.event_type
                FROM webhook_deliveries d JOIN outbox o ON o.event_id = d.event_id
                """)
            .query()
            .listOfRows();
    assertThat(rows).hasSize(4);
    for (Map<String, Object> r : rows) {
      assertThat(r)
          .containsEntry("state", "PENDING")
          .containsEntry("attempts", 0)
          .containsEntry("due_now", true)
          .containsEntry("same_payload", true)
          .containsEntry("seq", true)
          .containsEntry("same_order", true)
          .containsEntry("event_type", "order.created");
    }

    // An event handed over again, as after a relay commit that failed, is not delivered twice.
    List<Long> ids = jdbc.sql("SELECT id FROM outbox").query(Long.class).list();
    Integer again = tx.execute(s -> deliveries.enqueue(ids, clock.instant()));
    assertThat(again).isZero();
    assertThat(jdbc.sql("SELECT count(*) FROM webhook_deliveries").query(Integer.class).single())
        .isEqualTo(4);
  }

  @Test
  void aDeliveryIsSignedOverTheBytesSentAndSettledByA2xx() throws Exception {
    UUID ep = endpoint(merchant.url("/hooks/payflow"));
    UUID order = UUID.randomUUID();
    relayed(event(order, 1, "order.created", "acme"));
    WebhookDispatcher d = dispatcher(props());

    assertThat(d.runOnce()).isEqualTo(1);

    assertThat(merchant.received).hasSize(1);
    Received r = merchant.received.getFirst();
    assertThat(r.path()).isEqualTo("/hooks/payflow");
    String sig = r.header(WebhookSignature.HEADER);
    assertThat(sig).matches("t=" + clock.instant().getEpochSecond() + ",v1=[0-9a-f]{64}");
    byte[] body = r.body().getBytes(StandardCharsets.UTF_8);
    long now = clock.instant().getEpochSecond();
    assertThat(
            WebhookSignature.verify(
                sig, body, secrets.get(ep), now, WebhookSignature.DEFAULT_TOLERANCE))
        .isTrue();
    assertThat(
            WebhookSignature.verify(
                sig, body, "whsec_someone_else", now, WebhookSignature.DEFAULT_TOLERANCE))
        .isFalse();
    UUID delivery = onlyDelivery();
    String stored =
        jdbc.sql("SELECT payload::text FROM webhook_deliveries").query(String.class).single();
    assertThat(r.body()).isEqualTo(stored);
    assertThat(json.readTree(r.body()).get("order_id").asString()).isEqualTo(order.toString());
    assertThat(r.header("content-type")).isEqualTo("application/json");
    assertThat(r.header(WebhookDispatcher.HEADER_EVENT_TYPE)).isEqualTo("order.created");
    assertThat(r.header(WebhookDispatcher.HEADER_EVENT_ID))
        .isEqualTo(json.readTree(r.body()).get("event_id").asString());
    assertThat(r.header(WebhookDispatcher.HEADER_DELIVERY_ID)).isEqualTo(delivery.toString());
    assertThat(r.header(WebhookDispatcher.HEADER_ATTEMPT)).isEqualTo("1");
    assertThat(r.header("user-agent")).isEqualTo("payflow-webhooks/test");

    assertThat(state(delivery)).isEqualTo("SUCCEEDED");
    assertThat(nextAttempt(delivery)).isNull();
    List<Map<String, Object>> log = attempts(delivery);
    assertThat(log).hasSize(1);
    assertThat(log.getFirst())
        .containsEntry("response_status", 200)
        .containsEntry("response_body", "answer 200")
        .containsEntry("error", null);
    // Settled: nothing more is sent.
    assertThat(d.runOnce()).isZero();
    assertThat(merchant.received).hasSize(1);
  }

  @Test
  void failuresRetryWithFullJitterBackoffAndEveryAttemptIsRecorded() throws Exception {
    endpoint(merchant.url("/flaky"));
    relayed(event(UUID.randomUUID(), 1, "order.created", "acme"));
    AtomicInteger calls = new AtomicInteger();
    merchant.answer =
        r ->
            switch (calls.incrementAndGet()) {
              case 1 -> Reply.of(500);
              case 2 -> Reply.of(302);
              case 3 -> Reply.of(429);
              default -> Reply.of(204);
            };
    WebhookProperties p = props();
    Backoff ceilings = new Backoff(p.retryBase(), p.retryCap(), new SplittableRandom());
    WebhookDispatcher d = dispatcher(p);
    UUID delivery = null;
    for (int attempt = 0; attempt < 3; attempt++) {
      assertThat(d.runOnce()).isEqualTo(1);
      delivery = onlyDelivery();
      assertThat(state(delivery)).isEqualTo("PENDING");
      Duration wait = Duration.between(clock.instant(), nextAttempt(delivery));
      // Full jitter: anywhere from now to the attempt's ceiling, which doubles from the base.
      assertThat(wait).isBetween(Duration.ZERO, Duration.ofMillis(ceilings.ceilingMillis(attempt)));
      // Not due until then.
      if (!wait.isZero()) {
        assertThat(d.runOnce()).isZero();
      }
      clock.advance(wait);
    }
    assertThat(d.runOnce()).isEqualTo(1);
    assertThat(state(delivery)).isEqualTo("SUCCEEDED");
    assertThat(attempts(delivery))
        .extracting(a -> a.get("response_status"))
        .containsExactly(500, 302, 429, 204);
    // A redirect is an answer, not something to follow.
    assertThat(merchant.received).extracting(Received::path).containsOnly("/flaky");
    assertThat(jdbc.sql("SELECT attempts FROM webhook_deliveries").query(Integer.class).single())
        .isEqualTo(4);
  }

  @Test
  void retriesLastTheWindowWithTheFinalAttemptOnItsEdgeThenGiveUp() throws Exception {
    endpoint(merchant.url("/down"));
    merchant.answer = r -> Reply.of(503);
    relayed(event(UUID.randomUUID(), 1, "order.created", "acme"));
    Instant created = clock.instant();
    Duration window = Duration.ofDays(3);
    WebhookDispatcher d =
        dispatcher(props(Duration.ofSeconds(3), 8, 8, window, Duration.ofSeconds(1)));
    UUID delivery = onlyDelivery();
    int runs = 0;
    while (runs < 2_000) {
      assertThat(d.runOnce()).isEqualTo(1);
      runs++;
      Instant next = nextAttempt(delivery);
      if (next == null) {
        break;
      }
      assertThat(next).isAfterOrEqualTo(clock.instant()).isBeforeOrEqualTo(created.plus(window));
      clock.advance(Duration.between(clock.instant(), next));
    }
    assertThat(state(delivery)).isEqualTo("FAILED");
    List<Map<String, Object>> log = attempts(delivery);
    // Every attempt is visible, and the last was made at the very end of the window.
    assertThat(log).hasSize(runs).hasSize(merchant.received.size());
    assertThat(log).allSatisfy(a -> assertThat(a).containsEntry("response_status", 503));
    assertThat(((Timestamp) log.getLast().get("attempted_at")).toInstant())
        .isEqualTo(created.plus(window));
    // Backed off: three days at a 10-minute cap is a few hundred attempts, not thousands.
    assertThat(runs).isBetween(300, 1_200);
    // Three days of nothing but failures: the endpoint is disabled, and its final notice is the
    // one thing still sent to it, once.
    assertThat(merchant.received).hasSize(runs);
    assertThat(d.runOnce()).isEqualTo(1);
    assertThat(merchant.received.getLast().header(WebhookDispatcher.HEADER_EVENT_TYPE))
        .isEqualTo(WebhookEvents.ENDPOINT_DISABLED);
    assertThat(d.runOnce()).isZero();
  }

  @Test
  void oneOrdersEventsArriveInOrderWhileOtherOrdersCarryOn() throws Exception {
    endpoint(merchant.url("/ordered"));
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    long a1 = event(a, 1, "order.created", "acme");
    long b1 = event(b, 1, "order.created", "acme");
    long a2 = event(a, 2, "order.payment_authorized", "acme");
    long a3 = event(a, 3, "order.completed", "acme");
    relayed(a1, b1, a2, a3);
    Set<String> failedOnce = ConcurrentHashMap.newKeySet();
    String orderA = a.toString();
    merchant.answer =
        r ->
            json.readTree(r.body()).get("order_id").asString().equals(orderA)
                    && seq(r) == 1
                    && failedOnce.add("a1")
                ? Reply.of(500)
                : Reply.of(200);
    WebhookDispatcher d = dispatcher(props());

    // A's first event fails; its later events wait behind it, but B's goes through.
    assertThat(d.runOnce()).isEqualTo(2);
    assertThat(merchant.received)
        .extracting(r -> json.readTree(r.body()).get("order_id").asString() + "/" + seq(r))
        .containsExactlyInAnyOrder(a + "/1", b + "/1");
    clock.advance(Duration.ofHours(1));
    for (int i = 0; i < 5; i++) {
      d.runOnce();
    }
    List<Long> seqsForA =
        merchant.received.stream()
            .filter(r -> json.readTree(r.body()).get("order_id").asString().equals(orderA))
            .map(this::seq)
            .toList();
    assertThat(seqsForA).containsExactly(1L, 1L, 2L, 3L);
    assertThat(
            jdbc.sql("SELECT count(*) FROM webhook_deliveries WHERE state = 'SUCCEEDED'")
                .query(Integer.class)
                .single())
        .isEqualTo(4);
  }

  @Test
  void eachEndpointGetsOneOrdersEventsInOrderEvenWhenAnotherEndpointIsFailing() throws Exception {
    endpoint(merchant.url("/up"));
    endpoint(merchant.url("/down"));
    merchant.answer = r -> r.path().equals("/down") ? Reply.of(500) : Reply.of(200);
    UUID o = UUID.randomUUID();
    relayed(event(o, 1, "order.created", "acme"), event(o, 2, "order.completed", "acme"));
    WebhookDispatcher d = dispatcher(props());
    d.runOnce();
    d.runOnce();
    // The failing endpoint holds back only its own copy of the order's later event.
    assertThat(merchant.received.stream().filter(r -> r.path().equals("/up")).map(this::seq))
        .containsExactly(1L, 2L);
    assertThat(merchant.received.stream().filter(r -> r.path().equals("/down")).map(this::seq))
        .containsExactly(1L);
  }

  @Test
  void aSlowEndpointHoldsOnlyItsOwnSlots() throws Exception {
    endpoint(merchant.url("/slow"));
    endpoint(merchant.url("/fast"));
    merchant.answer = r -> r.path().equals("/slow") ? new Reply(200, 1_500) : Reply.of(200);
    List<Long> ids = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      ids.add(event(UUID.randomUUID(), 1, "order.created", "acme"));
    }
    relayed(ids.toArray(Long[]::new));
    long start = System.nanoTime();

    running(props(Duration.ofSeconds(3), 4, 2, Duration.ofDays(3), Duration.ofSeconds(3)));

    // Every fast delivery lands while the first slow ones are still waiting for their answer.
    await(
        () -> merchant.received.stream().filter(r -> r.path().equals("/fast")).count() == 6,
        Duration.ofSeconds(5));
    long fastDone =
        merchant.received.stream()
            .filter(r -> r.path().equals("/fast"))
            .mapToLong(Received::atNanos)
            .max()
            .orElseThrow();
    assertThat(Duration.ofNanos(fastDone - start)).isLessThan(Duration.ofMillis(1_200));
    await(
        () ->
            jdbc.sql("SELECT count(*) FROM webhook_deliveries WHERE state = 'SUCCEEDED'")
                    .query(Integer.class)
                    .single()
                == 12,
        Duration.ofSeconds(15));
    // Never more than the per-endpoint limit at the slow endpoint at once.
    assertThat(merchant.maxInFlight.get("/slow").get()).isEqualTo(2);
  }

  @Test
  void aClaimThatOutlivedItsLeaseCannotOverwriteTheNextOne() {
    endpoint(merchant.url("/x"));
    relayed(event(UUID.randomUUID(), 1, "order.created", "acme"));
    Duration lease = Duration.ofSeconds(30);
    Claim first = deliveries.claimDue(clock.instant(), lease, 10, Set.of()).getFirst();
    assertThat(deliveries.claimDue(clock.instant(), lease, 10, Set.of())).isEmpty();
    clock.advance(lease);
    Claim second = deliveries.claimDue(clock.instant(), lease, 10, Set.of()).getFirst();
    assertThat(second.id()).isEqualTo(first.id());
    assertThat(second.attempt()).isEqualTo(first.attempt() + 1);

    Attempt ok = new Attempt(clock.instant(), 5, 200, "ok", null);
    assertThat(deliveries.succeed(first, ok, clock.instant())).isFalse();
    assertThat(deliveries.retry(first, ok, clock.instant(), clock.instant())).isFalse();
    assertThat(deliveries.unclaim(first, clock.instant())).isFalse();
    assertThat(attempts(first.id())).isEmpty();
    assertThat(deliveries.succeed(second, ok, clock.instant())).isTrue();
    assertThat(attempts(first.id())).hasSize(1);
    assertThat(deliveries.succeed(second, ok, clock.instant())).isFalse();
  }

  @Test
  void theAddressIsCheckedAgainOnEverySend() throws Exception {
    // Rows written past the API's check, as if the name now resolved somewhere else.
    UUID literal = endpoint("http://10.0.0.1:9/hook");
    UUID named = endpoint("http://localhost:" + merchant.server.getAddress().getPort() + "/h");
    relayed(event(UUID.randomUUID(), 1, "order.created", "acme"));
    WebhookProperties p = props();
    WebhookDispatcher d = dispatcher(p, clock, AddressPolicy.strict());

    assertThat(d.runOnce()).isEqualTo(2);

    assertThat(merchant.received).isEmpty();
    Map<UUID, String> errors = new HashMap<>();
    jdbc.sql(
            """
            SELECT d.endpoint_id, a.error FROM webhook_attempts a
            JOIN webhook_deliveries d ON d.id = a.delivery_id
            """)
        .query((rs, i) -> errors.put(rs.getObject(1, UUID.class), rs.getString(2)))
        .list();
    assertThat(errors.get(literal)).startsWith("address_refused: 10.0.0.1 is").contains("private");
    assertThat(errors.get(named)).startsWith("address_refused: localhost resolves to");
    // Refused sends are attempts like any other: logged and retried.
    assertThat(
            jdbc.sql("SELECT count(*) FROM webhook_deliveries WHERE state = 'PENDING'")
                .query(Integer.class)
                .single())
        .isEqualTo(2);
  }

  @Test
  void noAnswerIsAFailedAttemptWithItsReason() throws Exception {
    // Nothing listens on the port of a server that was just stopped.
    String url = merchant.url("/gone");
    merchant.close();
    endpoint(url);
    relayed(event(UUID.randomUUID(), 1, "order.created", "acme"));
    dispatcher(props()).runOnce();
    Map<String, Object> a = attempts(onlyDelivery()).getFirst();
    assertThat(a.get("response_status")).isNull();
    assertThat((String) a.get("error")).startsWith("connect: 127.0.0.1");
    assertThat(state(onlyDelivery())).isEqualTo("PENDING");
  }

  @Test
  void disabledEndpointsWaitAndDeletedOnesGiveUp() throws Exception {
    UUID paused = endpoint(merchant.url("/paused"));
    UUID deleted = endpoint(merchant.url("/deleted"));
    relayed(event(UUID.randomUUID(), 1, "order.created", "acme"));
    endpointService.update(
        "acme",
        paused,
        new WebhookEndpointService.Changes(null, null, null, WebhookEndpoint.State.DISABLED));
    endpointService.delete("acme", deleted);
    WebhookDispatcher d = dispatcher(props());

    assertThat(d.runOnce()).isZero();
    assertThat(merchant.received).isEmpty();
    Map<UUID, String> states = new HashMap<>();
    jdbc.sql("SELECT endpoint_id, state FROM webhook_deliveries")
        .query((rs, i) -> states.put(rs.getObject(1, UUID.class), rs.getString(2)))
        .list();
    assertThat(states).containsOnly(Map.entry(paused, "PENDING"), Map.entry(deleted, "FAILED"));

    endpointService.update(
        "acme",
        paused,
        new WebhookEndpointService.Changes(null, null, null, WebhookEndpoint.State.ENABLED));
    assertThat(d.runOnce()).isEqualTo(1);
    assertThat(merchant.received).extracting(Received::path).containsExactly("/paused");
  }

  @Test
  void duringARotationEveryDeliveryCarriesBothSignaturesUntilTheOldSecretExpires()
      throws Exception {
    UUID ep = endpoint(merchant.url("/rotating"));
    String old = secrets.get(ep);
    String fresh = endpointService.rotateSecret("acme", ep, Duration.ofHours(1)).secret();
    WebhookDispatcher d = dispatcher(props());

    relayed(event(UUID.randomUUID(), 1, "order.created", "acme"));
    assertThat(d.runOnce()).isEqualTo(1);
    Received during = merchant.received.getLast();
    String sig = during.header(WebhookSignature.HEADER);
    long now = clock.instant().getEpochSecond();
    assertThat(sig).matches("t=" + now + ",v1=[0-9a-f]{64},v1=[0-9a-f]{64}");
    byte[] body = during.body().getBytes(StandardCharsets.UTF_8);
    // A receiver still on the old secret and one already on the new one both accept it, and the
    // first v1 is the new secret's.
    for (String s : List.of(old, fresh)) {
      assertThat(WebhookSignature.verify(sig, body, s, now, WebhookSignature.DEFAULT_TOLERANCE))
          .as("signed with " + (s.equals(old) ? "the old" : "the new") + " secret")
          .isTrue();
    }
    String firstOnly = sig.substring(0, sig.lastIndexOf(",v1="));
    assertThat(
            WebhookSignature.verify(
                firstOnly, body, fresh, now, WebhookSignature.DEFAULT_TOLERANCE))
        .isTrue();

    // Once it expires the old secret signs nothing more.
    clock.advance(Duration.ofHours(2));
    relayed(event(UUID.randomUUID(), 1, "order.created", "acme"));
    assertThat(d.runOnce()).isEqualTo(1);
    Received after = merchant.received.getLast();
    String sig2 = after.header(WebhookSignature.HEADER);
    long later = clock.instant().getEpochSecond();
    assertThat(sig2).matches("t=" + later + ",v1=[0-9a-f]{64}");
    byte[] body2 = after.body().getBytes(StandardCharsets.UTF_8);
    assertThat(
            WebhookSignature.verify(sig2, body2, fresh, later, WebhookSignature.DEFAULT_TOLERANCE))
        .isTrue();
    assertThat(WebhookSignature.verify(sig2, body2, old, later, WebhookSignature.DEFAULT_TOLERANCE))
        .isFalse();
  }

  @Test
  void anEndpointFailingForThreeDaysIsDisabledWithOneFinalNotice() throws Exception {
    UUID down = endpoint("acme", merchant.url("/down"), List.of("*"));
    UUID watcher =
        endpoint("acme", merchant.url("/watcher"), List.of(WebhookEvents.ENDPOINT_DISABLED));
    UUID orders = endpoint("acme", merchant.url("/orders"), List.of("order.created"));
    UUID all = endpoint("acme", merchant.url("/all"), List.of("*"));
    UUID globex = endpoint("globex", merchant.url("/globex"), List.of("*"));
    AtomicBoolean downWorks = new AtomicBoolean();
    merchant.answer = r -> Reply.of(r.path().equals("/down") && !downWorks.get() ? 503 : 200);
    WebhookDispatcher d = dispatcher(props());

    // The first failure starts the run.
    Instant t0 = clock.instant();
    relayed(event(UUID.randomUUID(), 1, "order.created", "acme"));
    d.runOnce();
    assertThat(stored(down).failingSince()).isEqualTo(t0.truncatedTo(ChronoUnit.MICROS));
    assertThat(stored(all).failingSince()).isNull();

    // A day of failures later, one 2xx ends the run.
    drive(d, t0.plus(Duration.ofDays(1)));
    clock.advance(Duration.between(clock.instant(), t0.plus(Duration.ofDays(1))));
    assertThat(stored(down).failingSince()).isEqualTo(t0.truncatedTo(ChronoUnit.MICROS));
    downWorks.set(true);
    relayed(event(UUID.randomUUID(), 1, "order.created", "acme"));
    drive(d, clock.instant());
    assertThat(stored(down).failingSince()).isNull();
    assertThat(stored(down).state()).isEqualTo(WebhookEndpoint.State.ENABLED);

    // It fails again. Just short of three days the endpoint is still enabled and retried.
    downWorks.set(false);
    Instant t1 = clock.instant();
    relayed(event(UUID.randomUUID(), 1, "order.created", "acme"));
    d.runOnce();
    assertThat(stored(down).failingSince()).isEqualTo(t1.truncatedTo(ChronoUnit.MICROS));
    drive(d, t1.plus(Duration.ofDays(3)).minusSeconds(1));
    assertThat(stored(down).state()).isEqualTo(WebhookEndpoint.State.ENABLED);

    // The first failure three days into the run disables it.
    clock.advance(Duration.between(clock.instant(), t1.plus(Duration.ofDays(3))));
    long sentBefore = merchant.received.stream().filter(r -> r.path().equals("/down")).count();
    relayed(event(UUID.randomUUID(), 1, "order.created", "acme"));
    while (stored(down).state() == WebhookEndpoint.State.ENABLED) {
      assertThat(d.runOnce()).isPositive();
    }
    WebhookEndpoint off = stored(down);
    assertThat(off.disabledReason()).isEqualTo(WebhookEndpoint.DisabledReason.FAILING);
    assertThat(off.failingSince()).isEqualTo(t1.truncatedTo(ChronoUnit.MICROS));
    assertThat(merchant.received.stream().filter(r -> r.path().equals("/down")).count())
        .isGreaterThan(sentBefore);

    // The notice goes, signed, to the endpoint itself and to the endpoints that subscribe to it;
    // the one that takes only orders, and the other merchant, get nothing.
    merchant.received.clear();
    while (d.runOnce() > 0) {
      Thread.onSpinWait();
    }
    List<Received> notices =
        merchant.received.stream()
            .filter(
                r ->
                    WebhookEvents.ENDPOINT_DISABLED.equals(
                        r.header(WebhookDispatcher.HEADER_EVENT_TYPE)))
            .toList();
    assertThat(notices)
        .extracting(Received::path)
        .containsExactlyInAnyOrder("/down", "/watcher", "/all");
    Received own = notices.stream().filter(r -> r.path().equals("/down")).findFirst().orElseThrow();
    assertThat(
            WebhookSignature.verify(
                own.header(WebhookSignature.HEADER),
                own.body().getBytes(StandardCharsets.UTF_8),
                secrets.get(down),
                clock.instant().getEpochSecond(),
                WebhookSignature.DEFAULT_TOLERANCE))
        .isTrue();
    JsonNode n = json.readTree(own.body());
    assertThat(n.get("event_type").asString()).isEqualTo(WebhookEvents.ENDPOINT_DISABLED);
    assertThat(n.get("merchant_id").asString()).isEqualTo("acme");
    assertThat(n.get("endpoint_id").asString()).isEqualTo(down.toString());
    assertThat(n.get("url").asString()).isEqualTo(merchant.url("/down"));
    assertThat(n.get("reason").asString()).isEqualTo("failing");
    assertThat(Instant.parse(n.get("failing_since").asString())).isEqualTo(off.failingSince());
    assertThat(n.has("order_id")).isFalse();
    assertThat(notices).extracting(Received::body).containsOnly(own.body());
    // Everything else to the disabled endpoint waits; its notice, refused, is not retried.
    assertThat(merchant.received).extracting(Received::path).doesNotContain("/orders", "/globex");
    assertThat(merchant.received.stream().filter(r -> r.path().equals("/down"))).hasSize(1);
    UUID ownNotice =
        jdbc.sql("SELECT id FROM webhook_deliveries WHERE endpoint_id = :e AND event_type = :t")
            .param("e", down)
            .param("t", WebhookEvents.ENDPOINT_DISABLED)
            .query(UUID.class)
            .single();
    assertThat(state(ownNotice)).isEqualTo("FAILED");
    assertThat(attempts(ownNotice)).hasSize(1);
    clock.advance(Duration.ofHours(6));
    assertThat(d.runOnce()).isZero();
    int waiting =
        jdbc.sql(
                "SELECT count(*) FROM webhook_deliveries WHERE endpoint_id = :e"
                    + " AND state = 'PENDING'")
            .param("e", down)
            .query(Integer.class)
            .single();
    assertThat(waiting).isPositive();
    assertThat(stored(watcher).state()).isEqualTo(WebhookEndpoint.State.ENABLED);
    assertThat(stored(orders).failingSince()).isNull();
    assertThat(endpointRepo.find("globex", globex)).isPresent();

    // The merchant fixes it and enables it again: a fresh start, and what waited goes out.
    downWorks.set(true);
    WebhookEndpoint on =
        endpointService.update(
            "acme",
            down,
            new WebhookEndpointService.Changes(null, null, null, WebhookEndpoint.State.ENABLED));
    assertThat(on.disabledReason()).isNull();
    assertThat(on.failingSince()).isNull();
    merchant.received.clear();
    while (d.runOnce() > 0) {
      Thread.onSpinWait();
    }
    assertThat(merchant.received.stream().filter(r -> r.path().equals("/down")).count())
        .isEqualTo(waiting);
  }

  @Test
  void aDeliveryThatFailsItsWholeWindowDisablesTheEndpointEvenIfItsFirstTryWasLate()
      throws Exception {
    UUID ep = endpoint(merchant.url("/late"));
    merchant.answer = r -> Reply.of(500);
    relayed(event(UUID.randomUUID(), 1, "order.created", "acme"));
    Instant created = clock.instant();
    // payflow was down for 40 minutes: the first try is that far into the window, so the
    // endpoint has failed for less than three days when the window ends.
    clock.advance(Duration.ofMinutes(40));
    WebhookDispatcher d = dispatcher(props());
    drive(d, created.plus(Duration.ofDays(3)).minusMillis(1));
    assertThat(stored(ep).state()).isEqualTo(WebhookEndpoint.State.ENABLED);
    drive(d, created.plus(Duration.ofDays(3)));
    WebhookEndpoint off = stored(ep);
    assertThat(off.state()).isEqualTo(WebhookEndpoint.State.DISABLED);
    assertThat(off.disabledReason()).isEqualTo(WebhookEndpoint.DisabledReason.FAILING);
    assertThat(Duration.between(off.failingSince(), clock.instant()))
        .isLessThan(Duration.ofDays(3));
  }

  @Test
  void aDeliveryFirstTriedLongAfterItsEventDoesNotDisableTheEndpoint() throws Exception {
    UUID ep = endpoint(merchant.url("/fresh"));
    merchant.answer = r -> Reply.of(500);
    relayed(event(UUID.randomUUID(), 1, "order.created", "acme"));
    // payflow was down for four days: the delivery's window is spent on its first try, but the
    // endpoint has failed once, not for three days.
    clock.advance(Duration.ofDays(4));
    WebhookDispatcher d = dispatcher(props());
    assertThat(d.runOnce()).isEqualTo(1);
    assertThat(state(onlyDelivery())).isEqualTo("FAILED");
    assertThat(stored(ep).state()).isEqualTo(WebhookEndpoint.State.ENABLED);
    assertThat(stored(ep).failingSince()).isNotNull();
    assertThat(d.runOnce()).isZero();
  }

  @Test
  void onlyTheEndpointsOwnFailuresCount() throws Exception {
    UUID broken = endpoint(merchant.url("/fine"));
    // Its secret was sealed with a key this instance doesn't have: payflow's fault, not the
    // endpoint's. Nothing is sent and the endpoint is not counted as failing.
    jdbc.sql("UPDATE webhook_endpoints SET secret_key_id = 'gone' WHERE id = :id")
        .param("id", broken)
        .update();
    relayed(event(UUID.randomUUID(), 1, "order.created", "acme"));
    WebhookDispatcher d = dispatcher(props());
    assertThat(d.runOnce()).isEqualTo(1);
    assertThat(merchant.received).isEmpty();
    assertThat(attempts(onlyDelivery()).getFirst().get("error").toString()).startsWith("secret");
    assertThat(stored(broken).failingSince()).isNull();

    // A failed ping counts, and a successful one ends the run.
    UUID pinged = endpoint(merchant.url("/ping"));
    merchant.answer = r -> Reply.of(502);
    tx.executeWithoutResult(
        s ->
            deliveries.enqueuePing(
                pinged, UUID.randomUUID(), "{\"event_type\":\"ping\"}", clock.instant()));
    assertThat(d.runOnce()).isEqualTo(1);
    assertThat(stored(pinged).failingSince()).isNotNull();
    merchant.answer = r -> Reply.of(204);
    tx.executeWithoutResult(
        s ->
            deliveries.enqueuePing(
                pinged, UUID.randomUUID(), "{\"event_type\":\"ping\"}", clock.instant()));
    assertThat(d.runOnce()).isEqualTo(1);
    assertThat(stored(pinged).failingSince()).isNull();
  }

  @Test
  void aStopHandsBackAnAttemptStillWaitingForItsAnswer() throws Exception {
    endpoint(merchant.url("/hang"));
    merchant.answer = r -> new Reply(200, 5_000);
    relayed(event(UUID.randomUUID(), 1, "order.created", "acme"));
    WebhookDispatcher d =
        running(props(Duration.ofSeconds(8), 4, 4, Duration.ofDays(3), Duration.ofMillis(300)));
    await(() -> merchant.received.size() == 1, Duration.ofSeconds(5));

    long t0 = System.nanoTime();
    d.stop();
    started.remove(d);
    assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(2));

    UUID delivery = onlyDelivery();
    assertThat(state(delivery)).isEqualTo("PENDING");
    assertThat(nextAttempt(delivery)).isBeforeOrEqualTo(Instant.now());
    List<Map<String, Object>> log = attempts(delivery);
    assertThat(log).hasSize(1);
    assertThat(log.getFirst().get("error"))
        .isEqualTo("shutdown: payflow stopped before the answer arrived");
    // The next instance sends it on its first poll.
    merchant.answer = r -> Reply.of(200);
    WebhookDispatcher next =
        dispatcher(props(), Clock.systemUTC(), AddressPolicy.allowing(List.of("127.0.0.1/32")));
    assertThat(next.runOnce()).isEqualTo(1);
    assertThat(state(delivery)).isEqualTo("SUCCEEDED");
    assertThat(merchant.received).hasSize(2);
    assertThat(merchant.received.get(1).header(WebhookDispatcher.HEADER_ATTEMPT)).isEqualTo("2");
  }

  @Test
  void attemptRowsKeepOnlyWhatTheColumnsHold() {
    Attempt a = new Attempt(Instant.EPOCH, -3, 500, "x\0y" + "z".repeat(2000), null);
    assertThat(a.body()).hasSize(1024).startsWith("x�y");
    assertThat(a.latencyMs()).isZero();
    assertThat(new Attempt(Instant.EPOCH, 1, null, null, "e".repeat(600)).error()).hasSize(512);
    assertThatThrownBy(() -> new Attempt(Instant.EPOCH, 1, 200, null, "both"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Attempt(Instant.EPOCH, 1, null, null, null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
