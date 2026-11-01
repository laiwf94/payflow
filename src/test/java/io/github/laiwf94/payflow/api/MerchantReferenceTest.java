package io.github.laiwf94.payflow.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.laiwf94.payflow.support.Api;
import io.github.laiwf94.payflow.support.Db;
import io.github.laiwf94.payflow.support.TestKeys;
import java.util.ArrayList;
import java.util.List;
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

/** {@code merchant_reference}: stored, returned, unique per merchant, and replayable when taken. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MerchantReferenceTest {

  @LocalServerPort int port;
  @Autowired JdbcClient jdbc;

  private Api api;

  @BeforeEach
  void setUp() {
    Db.truncateAll(jdbc);
    api = new Api(port, TestKeys.ACME);
  }

  private int orders() {
    return jdbc.sql("SELECT count(*) FROM orders").query(Integer.class).single();
  }

  @Test
  void theReferenceIsStoredAndReturned() {
    Api.Response created = api.post("/v1/orders", Api.orderBody("cart-1001"));
    assertThat(created.status()).as(created.body()).isEqualTo(201);
    assertThat(created.json().get("merchant_reference").asString()).isEqualTo("cart-1001");
    String id = created.json().get("id").asString();
    assertThat(api.get("/v1/orders/" + id).json().get("merchant_reference").asString())
        .isEqualTo("cart-1001");

    Api.Response without = api.post("/v1/orders", Api.orderBody());
    assertThat(without.status()).isEqualTo(201);
    assertThat(without.json().has("merchant_reference")).isFalse();
    assertThat(api.post("/v1/orders", Api.orderBody()).status())
        .as("orders without a reference never collide")
        .isEqualTo(201);
  }

  @Test
  void aTakenReferenceIs409AndCreatesNothing() {
    Api.Response first = api.post("/v1/orders", Api.orderBody("cart-7"), "Idempotency-Key", "a");
    String id = first.json().get("id").asString();

    Api.Response second =
        api.post(
            "/v1/orders",
            Api.orderBody("cart-7").replace("pm_card_ok", "pm_card_other"),
            "Idempotency-Key",
            "b");
    assertThat(second.status()).isEqualTo(409);
    assertThat(second.errorCode()).isEqualTo("order_reference_in_use");
    assertThat(second.json().at("/error/message").asString())
        .isEqualTo("merchant_reference is already used by order " + id);
    assertThat(orders()).isEqualTo(1);
    assertThat(
            jdbc.sql("SELECT count(*) FROM outbox WHERE event_type = 'order.created'")
                .query(Integer.class)
                .single())
        .as("no event for an order that was never created")
        .isEqualTo(1);
  }

  @Test
  void bothAnswersReplayWithTheirOwnKeys() {
    Api.Response created = api.post("/v1/orders", Api.orderBody("cart-9"), "Idempotency-Key", "a");
    Api.Response taken = api.post("/v1/orders", Api.orderBody("cart-9"), "Idempotency-Key", "b");
    assertThat(taken.status()).isEqualTo(409);

    Api.Response createdAgain =
        api.post("/v1/orders", Api.orderBody("cart-9"), "Idempotency-Key", "a");
    assertThat(createdAgain.status()).isEqualTo(201);
    assertThat(createdAgain.body()).isEqualTo(created.body());
    assertThat(createdAgain.header("Idempotent-Replayed")).isEqualTo("true");

    Api.Response takenAgain =
        api.post("/v1/orders", Api.orderBody("cart-9"), "Idempotency-Key", "b");
    assertThat(takenAgain.status()).isEqualTo(409);
    assertThat(takenAgain.body()).isEqualTo(taken.body());
    assertThat(takenAgain.header("Idempotent-Replayed")).isEqualTo("true");
    assertThat(orders()).isEqualTo(1);
  }

  @Test
  void referencesAreScopedToTheMerchant() {
    assertThat(api.post("/v1/orders", Api.orderBody("shared-ref")).status()).isEqualTo(201);
    Api.Response globex = api.as(TestKeys.GLOBEX).post("/v1/orders", Api.orderBody("shared-ref"));
    assertThat(globex.status()).as(globex.body()).isEqualTo(201);
    Api.Response again = api.as(TestKeys.GLOBEX).post("/v1/orders", Api.orderBody("shared-ref"));
    assertThat(again.errorCode()).isEqualTo("order_reference_in_use");
    assertThat(again.json().at("/error/message").asString())
        .endsWith(globex.json().get("id").asString());
    assertThat(orders()).isEqualTo(2);
  }

  @Test
  void theFormatIsCheckedAtTheEdge() {
    String longest = "r".repeat(128);
    String punctuation = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~";
    for (String ok : List.of(longest, punctuation, "A")) {
      Api.Response r = api.post("/v1/orders", Api.orderBody(ok));
      assertThat(r.status()).as(ok + ": " + r.body()).isEqualTo(201);
      assertThat(r.json().get("merchant_reference").asString()).isEqualTo(ok);
    }
    for (String bad : List.of("", "has space", "r".repeat(129), "caf\u00e9")) {
      Api.Response r = api.post("/v1/orders", Api.orderBody(bad));
      assertThat(r.status()).as(bad).isEqualTo(400);
      assertThat(r.json().at("/error/message").asString()).startsWith("merchant_reference:");
    }
    Api.Response control =
        api.post("/v1/orders", Api.orderBody("bell").replace("bell", "b\\u0007"));
    assertThat(control.status()).as(control.body()).isEqualTo(400);
    assertThat(control.json().at("/error/message").asString()).startsWith("merchant_reference:");
    assertThat(orders()).isEqualTo(3);
  }

  @Test
  void concurrentCreatesWithOneReferenceMakeOneOrder() throws Exception {
    int n = 20;
    ExecutorService pool = Executors.newFixedThreadPool(n);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Api.Response>> futures = new ArrayList<>();
    try {
      for (int i = 0; i < n; i++) {
        String key = "race-" + i;
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  return api.post("/v1/orders", Api.orderBody("race"), "Idempotency-Key", key);
                }));
      }
      start.countDown();
      List<Api.Response> responses = new ArrayList<>();
      for (Future<Api.Response> f : futures) {
        responses.add(f.get());
      }
      List<Api.Response> created = responses.stream().filter(r -> r.status() == 201).toList();
      assertThat(created).hasSize(1);
      String id = created.get(0).json().get("id").asString();
      assertThat(responses)
          .filteredOn(r -> r.status() != 201)
          .hasSize(n - 1)
          .allSatisfy(
              r -> {
                assertThat(r.status()).isEqualTo(409);
                assertThat(r.errorCode()).isEqualTo("order_reference_in_use");
                assertThat(r.json().at("/error/message").asString()).endsWith(id);
              });
    } finally {
      pool.shutdownNow();
    }
    assertThat(orders()).isEqualTo(1);
  }
}
