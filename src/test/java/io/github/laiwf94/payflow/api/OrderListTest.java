package io.github.laiwf94.payflow.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.laiwf94.payflow.money.Money;
import io.github.laiwf94.payflow.order.LineItem;
import io.github.laiwf94.payflow.order.Order;
import io.github.laiwf94.payflow.order.OrderRepository;
import io.github.laiwf94.payflow.order.Orders;
import io.github.laiwf94.payflow.support.Api;
import io.github.laiwf94.payflow.support.Db;
import io.github.laiwf94.payflow.support.TestKeys;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/** {@code GET /v1/orders} over HTTP: pages, filters, cursors and merchant isolation. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderListTest {

  private static final Instant T0 = Instant.parse("2026-11-02T09:00:00Z");

  @LocalServerPort int port;
  @Autowired JdbcClient jdbc;
  @Autowired OrderRepository repo;

  private Api acme;
  private Api globex;

  @BeforeEach
  void setUp() {
    Db.truncateAll(jdbc);
    acme = new Api(port, TestKeys.ACME);
    globex = new Api(port, TestKeys.GLOBEX);
  }

  private Order insert(String merchant, Instant at, String currency, String reference) {
    Order o =
        Order.create(
            UUID.randomUUID(),
            merchant,
            reference,
            Money.of(1500, currency),
            List.of(new LineItem("sku-1", 1, 1500)),
            Orders.ADDRESS,
            "pm_card_ok",
            "corr-" + UUID.randomUUID(),
            at);
    assertThat(repo.insert(o)).isTrue();
    return o;
  }

  private static List<String> ids(Api.Response r) {
    List<String> out = new ArrayList<>();
    r.json().get("data").forEach(o -> out.add(o.get("id").asString()));
    return out;
  }

  private static String q(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8);
  }

  @Test
  void anEmptyListingIsOnePageWithNoCursor() {
    Api.Response r = acme.get("/v1/orders");
    assertThat(r.status()).as(r.body()).isEqualTo(200);
    assertThat(r.json().get("data").isEmpty()).isTrue();
    assertThat(r.json().get("has_more").asBoolean()).isFalse();
    assertThat(r.json().has("next_cursor")).isFalse();
  }

  @Test
  void pagesRunNewestFirstAndEndWithoutACursor() {
    List<String> expected = new ArrayList<>();
    for (int i = 0; i < 7; i++) {
      expected.addFirst(insert("acme", T0.plusSeconds(i), "EUR", null).id().toString());
    }
    Api.Response first = acme.get("/v1/orders?limit=3");
    assertThat(ids(first)).isEqualTo(expected.subList(0, 3));
    assertThat(first.json().get("has_more").asBoolean()).isTrue();
    String cursor = first.json().get("next_cursor").asString();

    Api.Response second = acme.get("/v1/orders?limit=3&cursor=" + cursor);
    assertThat(ids(second)).isEqualTo(expected.subList(3, 6));
    Api.Response third =
        acme.get("/v1/orders?limit=3&cursor=" + second.json().get("next_cursor").asString());
    assertThat(ids(third)).isEqualTo(expected.subList(6, 7));
    assertThat(third.json().get("has_more").asBoolean()).isFalse();
    assertThat(third.json().has("next_cursor")).isFalse();

    JsonNode order = first.json().get("data").get(0);
    assertThat(order.get("amount").asLong()).isEqualTo(1500);
    assertThat(order.get("state").asString()).isEqualTo("PENDING");
    assertThat(acme.get("/v1/orders").json().get("data")).as("default limit 20").hasSize(7);
  }

  @Test
  void aPageThatEndsInsideATieResumesAfterItsLastOrder() {
    Set<String> all = new HashSet<>();
    for (int i = 0; i < 9; i++) {
      all.add(insert("acme", T0, "EUR", null).id().toString());
    }
    List<String> seen = new ArrayList<>();
    String cursor = null;
    do {
      Api.Response r = acme.get("/v1/orders?limit=2" + (cursor == null ? "" : "&cursor=" + cursor));
      seen.addAll(ids(r));
      cursor = r.json().path("next_cursor").asString(null);
    } while (cursor != null);
    assertThat(seen).hasSize(9).doesNotHaveDuplicates();
    assertThat(new HashSet<>(seen)).isEqualTo(all);
  }

  @Test
  void filtersCombine() {
    Order eurOld = insert("acme", T0, "EUR", "cart-1");
    Order usd = insert("acme", T0.plusSeconds(10), "USD", "cart-2");
    Order eurNew = insert("acme", T0.plusSeconds(20), "EUR", null);
    jdbc.sql("UPDATE orders SET state = 'RELEASING_INVENTORY' WHERE id = :id")
        .param("id", eurNew.id())
        .update();
    insert("globex", T0.plusSeconds(5), "EUR", "cart-1");

    assertThat(ids(acme.get("/v1/orders?currency=EUR")))
        .containsExactly(eurNew.id().toString(), eurOld.id().toString());
    assertThat(ids(acme.get("/v1/orders?state=PENDING")))
        .containsExactly(usd.id().toString(), eurOld.id().toString());
    assertThat(ids(acme.get("/v1/orders?state=PENDING&state=RELEASING_INVENTORY"))).hasSize(3);
    assertThat(ids(acme.get("/v1/orders?state=RELEASING_INVENTORY,FAILED")))
        .containsExactly(eurNew.id().toString());
    assertThat(
            ids(
                acme.get(
                    "/v1/orders?created_gte="
                        + q(T0.plusSeconds(10).toString())
                        + "&created_lt="
                        + q(T0.plusSeconds(20).toString()))))
        .as("from inclusive, before exclusive")
        .containsExactly(usd.id().toString());
    assertThat(ids(acme.get("/v1/orders?created_gte=" + q("2026-11-02T10:00:10+01:00"))))
        .as("offsets are honoured")
        .containsExactly(eurNew.id().toString(), usd.id().toString());
    assertThat(ids(acme.get("/v1/orders?currency=EUR&state=PENDING")))
        .containsExactly(eurOld.id().toString());
    assertThat(ids(acme.get("/v1/orders?currency=GBP"))).isEmpty();
  }

  @Test
  void findsAnOrderByItsMerchantReference() {
    Order mine = insert("acme", T0, "EUR", "cart-1001");
    insert("acme", T0.plusSeconds(1), "EUR", "cart-1002");
    insert("globex", T0, "EUR", "cart-1001");
    Api.Response r = acme.get("/v1/orders?merchant_reference=cart-1001");
    assertThat(ids(r)).containsExactly(mine.id().toString());
    assertThat(r.json().get("data").get(0).get("merchant_reference").asString())
        .isEqualTo("cart-1001");
    assertThat(r.json().get("has_more").asBoolean()).isFalse();
    assertThat(ids(acme.get("/v1/orders?merchant_reference=" + q("odd/#ref?")))).isEmpty();
    assertThat(ids(acme.get("/v1/orders?merchant_reference=cart-9999"))).isEmpty();
  }

  @Test
  void ordersCreatedThroughTheApiAreListed() {
    Api.Response created = acme.post("/v1/orders", Api.orderBody("api-ref-1"));
    assertThat(created.status()).isEqualTo(201);
    String id = created.json().get("id").asString();
    Api.Response r = acme.get("/v1/orders?merchant_reference=api-ref-1");
    assertThat(r.json().get("data").get(0)).isEqualTo(acme.get("/v1/orders/" + id).json());
  }

  @Test
  void eachMerchantSeesOnlyItsOwnOrders() {
    Order a = insert("acme", T0, "EUR", null);
    Order g = insert("globex", T0, "EUR", null);
    assertThat(ids(acme.get("/v1/orders"))).containsExactly(a.id().toString());
    assertThat(ids(globex.get("/v1/orders"))).containsExactly(g.id().toString());
  }

  @Test
  void aCursorOnlyWorksForTheListingThatIssuedIt() {
    for (int i = 0; i < 3; i++) {
      insert("acme", T0.plusSeconds(i), "EUR", null);
      insert("globex", T0.plusSeconds(i), "EUR", null);
    }
    String cursor =
        acme.get("/v1/orders?limit=1&currency=EUR").json().get("next_cursor").asString();
    assertThat(acme.get("/v1/orders?limit=2&currency=EUR&cursor=" + cursor).status())
        .as("the limit may change between pages")
        .isEqualTo(200);

    for (Api.Response r :
        List.of(
            globex.get("/v1/orders?limit=1&currency=EUR&cursor=" + cursor),
            acme.get("/v1/orders?limit=1&cursor=" + cursor),
            acme.get("/v1/orders?limit=1&currency=USD&cursor=" + cursor),
            acme.get("/v1/orders?limit=1&currency=EUR&cursor=" + cursor.substring(1) + "A"),
            acme.get("/v1/orders?limit=1&currency=EUR&cursor=nonsense"))) {
      assertThat(r.status()).as(r.body()).isEqualTo(400);
      assertThat(r.errorCode()).isEqualTo("invalid_cursor");
      assertThat(r.json().get("error").get("request_id").asString()).isNotBlank();
    }
  }

  @Test
  void badParametersAre400s() {
    for (String query :
        List.of(
            "status=FAILED",
            "limit=0",
            "limit=101",
            "limit=ten",
            "state=done",
            "currency=eur",
            "created_gte=2026-11-02",
            "created_gte=2026-11-03T00:00:00Z&created_lt=2026-11-02T00:00:00Z",
            "limit=1&limit=2",
            "merchant_reference=" + "r".repeat(129))) {
      Api.Response r = acme.get("/v1/orders?" + query);
      assertThat(r.status()).as(query + ": " + r.body()).isEqualTo(400);
      assertThat(r.errorCode()).as(query).isEqualTo("invalid_argument");
    }
  }

  @Test
  void needsAnApiKey() {
    Api.Response r = new Api(port, null).get("/v1/orders");
    assertThat(r.status()).isEqualTo(401);
    assertThat(r.errorCode()).isEqualTo("unauthenticated");
  }

  /**
   * Real concurrency, not the property's interleaving: two threads insert orders for both
   * merchants, some at times the cursor has already passed, while acme pages through 300 orders.
   */
  @Test
  void pagingDuringConcurrentInsertsSeesEveryEarlierOrderOnce() throws Exception {
    Set<String> before = new HashSet<>();
    for (int i = 0; i < 300; i++) {
      before.add(insert("acme", T0.plusMillis(i * 7L % 1000), "EUR", null).id().toString());
      insert("globex", T0.plusMillis(i), "EUR", null);
    }
    AtomicBoolean stop = new AtomicBoolean();
    AtomicInteger added = new AtomicInteger();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    List<Future<?>> writers = new ArrayList<>();
    for (int w = 0; w < 2; w++) {
      writers.add(
          pool.submit(
              () -> {
                int n = 0;
                while (!stop.get()) {
                  Instant at =
                      n % 2 == 0
                          ? Instant.now().truncatedTo(ChronoUnit.MICROS)
                          : T0.plusMillis(n % 1000);
                  insert(n % 3 == 0 ? "globex" : "acme", at, "EUR", null);
                  added.incrementAndGet();
                  n++;
                }
              }));
    }
    List<String> seen = new ArrayList<>();
    try {
      while (added.get() < 10) {
        Thread.onSpinWait();
      }
      String cursor = null;
      do {
        Api.Response r =
            acme.get("/v1/orders?limit=37" + (cursor == null ? "" : "&cursor=" + cursor));
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        seen.addAll(ids(r));
        cursor = r.json().path("next_cursor").asString(null);
      } while (cursor != null);
    } finally {
      stop.set(true);
      for (Future<?> f : writers) {
        f.get();
      }
      pool.shutdown();
    }
    assertThat(added.get()).as("writers ran during paging").isPositive();
    assertThat(seen).doesNotHaveDuplicates();
    assertThat(seen).containsAll(before);
  }
}
