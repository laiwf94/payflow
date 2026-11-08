package io.github.laiwf94.payflow.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.laiwf94.payflow.sim.Faults;
import io.github.laiwf94.payflow.sim.PaymentProviderSimulator;
import io.github.laiwf94.payflow.support.Api;
import io.github.laiwf94.payflow.support.TestKeys;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.JsonNode;

/**
 * Partial and line refunds over HTTP, with the saga and the refund worker running against the
 * in-process provider: the order's money, the refund rows and the provider's ledger agree, and
 * refunds racing each other never return more than was captured or bought.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"payflow.saga.enabled=true"})
@DirtiesContext
class RefundApiTest {

  private static final String ONE_MUG = "{\"lines\":[{\"sku\":\"sku-1\",\"quantity\":1}]}";

  @LocalServerPort int port;
  @Autowired Faults faults;
  @Autowired PaymentProviderSimulator provider;

  private Api api;

  @BeforeEach
  void setUp() {
    api = new Api(port, TestKeys.ACME);
    faults.reset(new SplittableRandom(7));
  }

  private String completedOrder() {
    Api.Response r = api.post("/v1/orders", Api.orderBody());
    assertThat(r.status()).as(r.body()).isEqualTo(201);
    String id = r.json().get("id").asString();
    awaitOrder(id, "COMPLETED");
    return id;
  }

  private JsonNode awaitOrder(String id, String state) {
    await()
        .atMost(Duration.ofSeconds(20))
        .until(() -> api.get("/v1/orders/" + id).json().get("state").asString().equals(state));
    return api.get("/v1/orders/" + id).json();
  }

  private JsonNode settledRefunds(String id) {
    await()
        .atMost(Duration.ofSeconds(20))
        .until(
            () -> {
              JsonNode data = api.get("/v1/orders/" + id + "/refunds").json().get("data");
              for (JsonNode r : data) {
                if (r.get("state").asString().equals("PENDING")) {
                  return false;
                }
              }
              return true;
            });
    return api.get("/v1/orders/" + id + "/refunds").json().get("data");
  }

  /** The provider's refunds of the order's payment, as (reference, amount) pairs. */
  private List<String> providerRefunds(String orderId) {
    String payment = api.get("/v1/orders/" + orderId).json().at("/payment/id").asString();
    return provider.refundsFor(payment).stream()
        .map(r -> r.reference() + ":" + r.amount())
        .toList();
  }

  @Test
  void amountAndLineRefundsAddUpToARefundedOrder() {
    String id = completedOrder();

    Api.Response first = api.post("/v1/orders/" + id + "/refunds", "{\"amount\":300}");
    assertThat(first.status()).isEqualTo(202);
    assertThat(first.json().get("currency").asString()).isEqualTo("EUR");
    assertThat(first.json().has("lines")).isFalse();
    JsonNode partial = awaitOrder(id, "PARTIALLY_REFUNDED");
    assertThat(partial.at("/payment/refunded_amount").asLong()).isEqualTo(300);

    Api.Response mug = api.post("/v1/orders/" + id + "/refunds", ONE_MUG);
    assertThat(mug.status()).isEqualTo(202);
    assertThat(mug.json().get("amount").asLong()).isEqualTo(1000);
    assertThat(mug.json().at("/lines/0/sku").asString()).isEqualTo("sku-1");
    assertThat(mug.json().at("/lines/0/quantity").asInt()).isEqualTo(1);

    // 1,200 is left: the other mug (1,000) fits, the tee as well as the mug (1,500) does not.
    Api.Response tooMuch =
        api.post(
            "/v1/orders/" + id + "/refunds",
            "{\"lines\":[{\"sku\":\"sku-1\",\"quantity\":1},{\"sku\":\"sku-2\",\"quantity\":1}]}");
    assertThat(tooMuch.status()).isEqualTo(409);
    assertThat(tooMuch.errorCode()).isEqualTo("refund_exceeds_captured");
    assertThat(tooMuch.json().at("/error/message").asString())
        .isEqualTo("refund of 1500 exceeds the 1200 left to refund");

    Api.Response rest = api.post("/v1/orders/" + id + "/refunds", "{\"amount\":1200}");
    assertThat(rest.status()).isEqualTo(202);
    JsonNode refunded = awaitOrder(id, "REFUNDED");
    assertThat(refunded.at("/payment/refunded_amount").asLong()).isEqualTo(2500);

    JsonNode refunds = settledRefunds(id);
    assertThat(refunds)
        .extracting(r -> r.get("amount").asLong())
        .containsExactly(300L, 1000L, 1200L);
    assertThat(refunds).extracting(r -> r.get("state").asString()).containsOnly("SUCCEEDED");
    assertThat(refunds)
        .extracting(r -> r.get("id").asString() + ":" + r.get("amount").asLong())
        .containsExactlyInAnyOrderElementsOf(providerRefunds(id));

    Api.Response after = api.post("/v1/orders/" + id + "/refunds", "{\"amount\":1}");
    assertThat(after.errorCode()).isEqualTo("order_not_refundable");
  }

  @Test
  void aRefusedRefundFailsAndGivesItsShareBack() {
    String id = completedOrder();
    String payment = api.get("/v1/orders/" + id).json().at("/payment/id").asString();
    // 10.00 was refunded at the provider directly, so it refuses to return all 25.00.
    provider.refund(null, payment, 1000, "out-of-band");
    assertThat(api.post("/v1/orders/" + id + "/refunds", "{\"amount\":2500}").status())
        .isEqualTo(202);
    JsonNode failed = settledRefunds(id).get(0);
    assertThat(failed.get("state").asString()).isEqualTo("FAILED");
    assertThat(failed.at("/failure/code").asString()).isEqualTo("provider_refused");
    assertThat(failed.at("/failure/message").asString()).isNotBlank();
    assertThat(api.get("/v1/orders/" + id).json().at("/payment/refunded_amount").asLong()).isZero();

    assertThat(api.post("/v1/orders/" + id + "/refunds", "{\"amount\":1500}").status())
        .as("the failed refund no longer counts against the capture")
        .isEqualTo(202);
    assertThat(awaitOrder(id, "PARTIALLY_REFUNDED").at("/payment/refunded_amount").asLong())
        .isEqualTo(1500);
  }

  @Test
  void racingAmountRefundsNeverExceedTheCapture() throws Exception {
    String id = completedOrder();
    List<Api.Response> answers =
        race(10, () -> api.post("/v1/orders/" + id + "/refunds", "{\"amount\":600}"));
    assertThat(answers).filteredOn(r -> r.status() == 202).hasSize(4);
    assertThat(answers)
        .filteredOn(r -> r.status() != 202)
        .extracting(Api.Response::errorCode)
        .containsOnly("refund_exceeds_captured")
        .hasSize(6);

    JsonNode refunds = settledRefunds(id);
    assertThat(refunds).hasSize(4);
    JsonNode order = awaitOrder(id, "PARTIALLY_REFUNDED");
    assertThat(order.at("/payment/refunded_amount").asLong()).isEqualTo(2400);
    assertThat(providerRefunds(id)).hasSize(4);
  }

  @Test
  void racingLineRefundsNeverReturnMoreThanWasBought() throws Exception {
    String id = completedOrder();
    List<Api.Response> answers = race(6, () -> api.post("/v1/orders/" + id + "/refunds", ONE_MUG));
    assertThat(answers).filteredOn(r -> r.status() == 202).hasSize(2);
    assertThat(answers)
        .filteredOn(r -> r.status() != 202)
        .extracting(Api.Response::errorCode)
        .containsOnly("refund_exceeds_purchased")
        .hasSize(4);

    JsonNode refunds = settledRefunds(id);
    assertThat(refunds).extracting(r -> r.get("amount").asLong()).containsExactly(1000L, 1000L);
    assertThat(awaitOrder(id, "PARTIALLY_REFUNDED").at("/payment/refunded_amount").asLong())
        .isEqualTo(2000);
    Api.Response noMore = api.post("/v1/orders/" + id + "/refunds", ONE_MUG);
    assertThat(noMore.errorCode()).isEqualTo("refund_exceeds_purchased");
    assertThat(noMore.json().at("/error/message").asString())
        .isEqualTo("refund of 1 of sku-1 exceeds the 0 left to refund");
  }

  @Test
  void aReplayedRefundIsTheSameRefund() {
    String id = completedOrder();
    Api.Response first =
        api.post("/v1/orders/" + id + "/refunds", "{\"amount\":700}", "Idempotency-Key", "r-1");
    Api.Response again =
        api.post("/v1/orders/" + id + "/refunds", "{\"amount\":700}", "Idempotency-Key", "r-1");
    assertThat(again.header("Idempotent-Replayed")).isEqualTo("true");
    assertThat(again.json()).isEqualTo(first.json());
    assertThat(settledRefunds(id)).hasSize(1);
    Api.Response other =
        api.post("/v1/orders/" + id + "/refunds", "{\"amount\":701}", "Idempotency-Key", "r-1");
    assertThat(other.errorCode()).isEqualTo("idempotency_key_reused");
  }

  private interface Call {
    Api.Response send();
  }

  /** Sends {@code n} requests that all start at the same moment. */
  private static List<Api.Response> race(int n, Call call) throws Exception {
    CyclicBarrier start = new CyclicBarrier(n);
    List<Future<Api.Response>> futures = new ArrayList<>();
    try (ExecutorService pool = Executors.newFixedThreadPool(n)) {
      for (int i = 0; i < n; i++) {
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  return call.send();
                }));
      }
      List<Api.Response> out = new ArrayList<>();
      for (Future<Api.Response> f : futures) {
        out.add(f.get());
      }
      return out;
    }
  }
}
