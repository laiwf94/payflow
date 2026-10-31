package io.github.laiwf94.payflow.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.laiwf94.payflow.order.OrderTransitions;
import io.github.laiwf94.payflow.order.Orders;
import io.github.laiwf94.payflow.support.Api;
import io.github.laiwf94.payflow.support.Db;
import io.github.laiwf94.payflow.support.TestKeys;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(IdempotencyTest.Failing.class)
class IdempotencyTest {

  /** An endpoint that writes an order and then fails, to show the write is undone. */
  @TestConfiguration(proxyBeanMethods = false)
  @RestController
  static class Failing {
    private final OrderTransitions transitions;

    Failing(OrderTransitions transitions) {
      this.transitions = transitions;
    }

    @PostMapping("/v1/test-failure")
    String fail() {
      transitions.create(Orders.pending("acme", Instant.now()));
      throw new IllegalStateException("simulated bug after the write");
    }
  }

  @LocalServerPort int port;
  @Autowired JdbcClient jdbc;
  @Autowired IdempotencyStore store;
  @Autowired FilterRegistrationBean<IdempotencyFilter> registration;

  private Api api;
  private IdempotencyFilter filter;

  @BeforeEach
  void setUp() {
    filter = registration.getFilter();
    Db.truncateAll(jdbc);
    api = new Api(port, TestKeys.ACME);
  }

  private int orders() {
    return jdbc.sql("SELECT count(*) FROM orders").query(Integer.class).single();
  }

  private int keys() {
    return jdbc.sql("SELECT count(*) FROM idempotency_keys").query(Integer.class).single();
  }

  @Test
  void sameKeySameBodyReplaysTheFirstResponse() {
    Api.Response first = api.post("/v1/orders", Api.orderBody(), "Idempotency-Key", "k-1");
    Api.Response second = api.post("/v1/orders", Api.orderBody(), "Idempotency-Key", "k-1");
    assertThat(first.status()).isEqualTo(201);
    assertThat(second.status()).isEqualTo(201);
    assertThat(second.body()).isEqualTo(first.body());
    assertThat(second.header("Location")).isEqualTo(first.header("Location"));
    assertThat(second.header("Idempotent-Replayed")).isEqualTo("true");
    assertThat(first.header("Idempotent-Replayed")).isNull();
    assertThat(orders()).isEqualTo(1);
  }

  @Test
  void fieldOrderAndWhitespaceDoNotMakeADifferentRequest() {
    String body = Api.orderBody();
    String reordered =
        "{\"payment_method\":\"pm_card_ok\","
            + body.substring(1, body.lastIndexOf(",\n \"payment_method\""))
            + "}";
    api.post("/v1/orders", body, "Idempotency-Key", "k-2");
    Api.Response r = api.post("/v1/orders", reordered, "Idempotency-Key", "k-2");
    assertThat(r.header("Idempotent-Replayed")).isEqualTo("true");
    assertThat(orders()).isEqualTo(1);
  }

  @Test
  void sameKeyDifferentRequestIs422() {
    api.post("/v1/orders", Api.orderBody(), "Idempotency-Key", "k-3");
    Api.Response body =
        api.post(
            "/v1/orders", Api.orderBody("pm_card_declined", "12345"), "Idempotency-Key", "k-3");
    assertThat(body.status()).isEqualTo(422);
    assertThat(body.errorCode()).isEqualTo("idempotency_key_reused");
    String id = orderIds().get(0).toString();
    Api.Response path = api.post("/v1/orders/" + id + "/cancel", null, "Idempotency-Key", "k-3");
    assertThat(path.errorCode()).isEqualTo("idempotency_key_reused");
    assertThat(orders()).isEqualTo(1);
  }

  @Test
  void errorsAreReplayedToo() {
    String bad = Api.orderBody().replace("\"EUR\"", "\"ZZZ\"");
    Api.Response first =
        api.post("/v1/orders", bad, "Idempotency-Key", "k-4", "X-Request-Id", "first");
    Api.Response again =
        api.post("/v1/orders", bad, "Idempotency-Key", "k-4", "X-Request-Id", "second");
    assertThat(first.status()).isEqualTo(400);
    assertThat(again.status()).isEqualTo(400);
    assertThat(again.body()).isEqualTo(first.body()).contains("\"request_id\":\"first\"");
    assertThat(again.header("X-Request-Id")).isEqualTo("second");
  }

  @Test
  void theKeyIsRequiredAndBounded() {
    for (String key : new String[] {"", "has space", "x".repeat(256), "tab\tkey"}) {
      Api.Response r = api.post("/v1/orders", Api.orderBody(), "Idempotency-Key", key);
      assertThat(r.status()).as(key).isEqualTo(400);
      assertThat(r.json().at("/error/message").asString()).contains("Idempotency-Key");
    }
    assertThat(api.post("/v1/orders", Api.orderBody(), "Idempotency-Key", "x".repeat(255)).status())
        .isEqualTo(201);
    assertThat(orders()).isEqualTo(1);
  }

  @Test
  void keysArePerMerchant() {
    Api.Response acme = api.post("/v1/orders", Api.orderBody(), "Idempotency-Key", "shared");
    Api.Response globex =
        api.as(TestKeys.GLOBEX).post("/v1/orders", Api.orderBody(), "Idempotency-Key", "shared");
    assertThat(globex.status()).isEqualTo(201);
    assertThat(globex.json().get("id")).isNotEqualTo(acme.json().get("id"));
  }

  @Test
  void concurrentDuplicatesCreateOneOrder() throws Exception {
    int n = 20;
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(n);
    try {
      List<Future<Api.Response>> results = new ArrayList<>();
      for (int i = 0; i < n; i++) {
        results.add(
            pool.submit(
                () -> {
                  start.await();
                  return api.post("/v1/orders", Api.orderBody(), "Idempotency-Key", "race");
                }));
      }
      start.countDown();
      Set<String> ids = new HashSet<>();
      int conflicts = 0;
      for (Future<Api.Response> f : results) {
        Api.Response r = f.get();
        if (r.status() == 409) {
          assertThat(r.errorCode()).isEqualTo("idempotency_key_in_use");
          conflicts++;
        } else {
          assertThat(r.status()).isEqualTo(201);
          ids.add(r.json().get("id").asString());
        }
      }
      assertThat(ids).hasSize(1);
      assertThat(conflicts).isLessThan(n);
      assertThat(orders()).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void anAbandonedKeyIsTakenOverAndALiveOneIsNot() {
    insertInProgress("abandoned", "now() - interval '1 second'");
    insertInProgress("live", "now() + interval '1 minute'");
    assertThat(api.post("/v1/orders", Api.orderBody(), "Idempotency-Key", "abandoned").status())
        .isEqualTo(201);
    Api.Response live = api.post("/v1/orders", Api.orderBody(), "Idempotency-Key", "live");
    assertThat(live.status()).isEqualTo(409);
    assertThat(live.errorCode()).isEqualTo("idempotency_key_in_use");
  }

  @Test
  void aServerErrorRollsTheWorkBackAndReleasesTheKey() {
    Api.Response r = api.post("/v1/test-failure", "{}", "Idempotency-Key", "boom");
    assertThat(r.status()).isEqualTo(500);
    assertThat(r.errorCode()).isEqualTo("internal");
    assertThat(r.body()).doesNotContain("simulated bug");
    assertThat(orders()).as("the order written before the failure rolled back").isZero();
    assertThat(keys()).isZero();
    assertThat(api.post("/v1/test-failure", "{}", "Idempotency-Key", "boom").status())
        .as("the retry runs again rather than replaying the 500")
        .isEqualTo(500);
  }

  @Test
  void aRequestThatLostItsLockCannotStoreItsResponse() {
    byte[] hash = filter.hash("POST", "/v1/orders", new byte[0]);
    IdempotencyStore.Claim c = store.claim("acme", "lock-lost", "POST", "/v1/orders", hash);
    assertThat(c).isInstanceOf(IdempotencyStore.Claim.Proceed.class);
    assertThat(store.complete("acme", "lock-lost", UUID.randomUUID(), 201, "{}", null)).isFalse();
    UUID token = ((IdempotencyStore.Claim.Proceed) c).token();
    assertThat(store.complete("acme", "lock-lost", token, 201, "{}", null)).isTrue();
    assertThat(store.claim("acme", "lock-lost", "POST", "/v1/orders", hash))
        .isEqualTo(new IdempotencyStore.Claim.Replay(201, "{}", null));
  }

  @Test
  void oldKeysExpire() {
    api.post("/v1/orders", Api.orderBody(), "Idempotency-Key", "fresh");
    jdbc.sql(
            """
            INSERT INTO idempotency_keys (merchant_id, idem_key, request_method, request_path,
                request_hash, response_status, response_body, created_at, completed_at)
            VALUES ('acme', 'old', 'POST', '/v1/orders', '\\x00', 201, '{}',
                now() - interval '2 days', now() - interval '2 days')
            """)
        .update();
    assertThat(store.expire()).isEqualTo(1);
    assertThat(keys()).isEqualTo(1);
  }

  @Test
  void canonicalHashing() {
    assertThat(
            filter.hash(
                "POST",
                "/a",
                "{\"b\":1,\"a\":[2,{\"y\":1,\"x\":2}]}"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)))
        .isEqualTo(
            filter.hash(
                "POST",
                "/a",
                "{ \"a\":[2,{\"x\":2,\"y\":1}], \"b\":1 }"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    assertThat(
            filter.hash(
                "POST", "/a", "{\"a\":1}".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
        .isNotEqualTo(
            filter.hash(
                "POST", "/b", "{\"a\":1}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    assertThat(
            filter.hash("POST", "/a", "not json".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
        .hasSize(32);
  }

  private void insertInProgress(String key, String lockedUntil) {
    jdbc.sql(
            """
            INSERT INTO idempotency_keys (merchant_id, idem_key, request_method, request_path,
                request_hash, lock_token, locked_until, created_at)
            VALUES ('acme', :key, 'POST', '/v1/orders', :hash, gen_random_uuid(), %s, now())
            """
                .formatted(lockedUntil))
        .param("key", key)
        .param(
            "hash",
            filter.hash(
                "POST",
                "/v1/orders",
                Api.orderBody().getBytes(java.nio.charset.StandardCharsets.UTF_8)))
        .update();
  }

  private List<UUID> orderIds() {
    return jdbc.sql("SELECT id FROM orders").query(UUID.class).list();
  }
}
