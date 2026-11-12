package io.github.laiwf94.payflow.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.laiwf94.payflow.sim.FaultPlan;
import io.github.laiwf94.payflow.sim.Faults;
import io.github.laiwf94.payflow.sim.InventorySimulator;
import io.github.laiwf94.payflow.support.Api;
import io.github.laiwf94.payflow.support.TestKeys;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.SplittableRandom;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;

/** Every endpoint and error code over real HTTP, with the saga, relay and consumer running. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "payflow.saga.enabled=true",
      "payflow.outbox.enabled=true",
      "payflow.timeline.enabled=true"
    })
@DirtiesContext
class OrderApiTest {

  @LocalServerPort int port;
  @Autowired Faults faults;
  @Autowired InventorySimulator inventory;

  private Api api;

  @BeforeEach
  void setUp() {
    api = new Api(port, TestKeys.ACME);
    faults.reset(new SplittableRandom(1));
  }

  private String create(String body) {
    Api.Response r = api.post("/v1/orders", body);
    assertThat(r.status()).as(r.body()).isEqualTo(201);
    return r.json().get("id").asString();
  }

  private Api.Response awaitState(String id, String state) {
    await()
        .atMost(Duration.ofSeconds(20))
        .until(() -> api.get("/v1/orders/" + id).json().get("state").asString().equals(state));
    return api.get("/v1/orders/" + id);
  }

  @Test
  void createRunsTheSagaToCompletion() {
    Api.Response r = api.post("/v1/orders", Api.orderBody(), "X-Request-Id", "req-create-1");
    assertThat(r.status()).isEqualTo(201);
    String id = r.json().get("id").asString();
    assertThat(r.header("Location")).isEqualTo("/v1/orders/" + id);
    assertThat(r.json().get("state").asString()).isEqualTo("PENDING");
    assertThat(r.json().get("amount").asLong()).isEqualTo(2500);
    assertThat(r.json().get("currency").asString()).isEqualTo("EUR");
    assertThat(r.json().get("items").get(0).get("unit_amount").asLong()).isEqualTo(1000);
    assertThat(r.json().get("shipping_address").get("postal_code").asString()).isEqualTo("12345");

    Api.Response done = awaitState(id, "COMPLETED");
    assertThat(done.json().at("/payment/captured_amount").asLong()).isEqualTo(2500);
    assertThat(done.json().at("/shipment/tracking_number").asString()).startsWith("TRK");
    assertThat(done.json().has("failure")).isFalse();

    await()
        .atMost(Duration.ofSeconds(20))
        .until(() -> api.get("/v1/orders/" + id + "/events").json().get("data").size() == 5);
    assertThat(api.get("/v1/orders/" + id + "/events").json().get("data"))
        .extracting(n -> n.get("event_type").asString())
        .containsExactly(
            "order.created",
            "order.inventory_reserved",
            "order.payment_authorized",
            "order.payment_captured",
            "order.completed");
  }

  @Test
  void declinedOrderShowsWhy() {
    String id = create(Api.orderBody("pm_card_declined", "12345"));
    Api.Response failed = awaitState(id, "FAILED");
    assertThat(failed.json().at("/failure/code").asString()).isEqualTo("payment_declined");
    assertThat(failed.json().at("/failure/message").asString())
        .isEqualTo("card declined: card_declined");
  }

  @Test
  void cancelBeforeCaptureAndConflictsAfter() {
    faults.set("inventory.reserve", new FaultPlan(0, 0, 0, Duration.ofMillis(800), List.of()));
    String id = create(Api.orderBody());
    // The simulator applies the reservation and then holds its answer, so from here the saga is
    // waiting on the call for 800 ms. Cancelling sooner could find the order not yet claimed and
    // let the worker cancel it outright, before the repeat arrives (#83).
    await().atMost(Duration.ofSeconds(10)).until(() -> inventory.reservation(id).isPresent());
    Api.Response c = api.post("/v1/orders/" + id + "/cancel", null);
    assertThat(c.status()).isEqualTo(202);
    assertThat(c.json().get("cancel_requested").asBoolean()).isTrue();
    assertThat(api.post("/v1/orders/" + id + "/cancel", null).status()).as("repeat").isEqualTo(202);
    awaitState(id, "CANCELLED");
    Api.Response again = api.post("/v1/orders/" + id + "/cancel", null);
    assertThat(again.status()).isEqualTo(409);
    assertThat(again.errorCode()).isEqualTo("order_not_cancellable");
  }

  @Test
  void refundAfterCompletion() {
    String id = create(Api.orderBody());
    assertThat(api.post("/v1/orders/" + id + "/refund", null).errorCode())
        .as("not complete yet, or completes in between")
        .isIn("order_not_refundable", null);
    awaitState(id, "COMPLETED");
    Api.Response cancel = api.post("/v1/orders/" + id + "/cancel", null);
    assertThat(cancel.status()).isEqualTo(409);
    Api.Response r = api.post("/v1/orders/" + id + "/refund", null);
    assertThat(r.status()).isIn(202);
    assertThat(r.json().get("state").asString())
        .as("the order moves when the refund succeeds, not when it is asked for")
        .isEqualTo("COMPLETED");
    Api.Response refunded = awaitState(id, "REFUNDED");
    assertThat(refunded.json().at("/payment/refunded_amount").asLong()).isEqualTo(2500);
    Api.Response again = api.post("/v1/orders/" + id + "/refund", null);
    assertThat(again.status()).isEqualTo(409);
    assertThat(again.errorCode()).isEqualTo("order_not_refundable");
  }

  @Test
  void ordersAreInvisibleToOtherMerchants() {
    String id = create(Api.orderBody());
    Api globex = api.as(TestKeys.GLOBEX);
    for (Api.Response r :
        List.of(
            globex.get("/v1/orders/" + id),
            globex.get("/v1/orders/" + id + "/events"),
            globex.post("/v1/orders/" + id + "/cancel", null),
            globex.post("/v1/orders/" + id + "/refund", null))) {
      assertThat(r.status()).isEqualTo(404);
      assertThat(r.errorCode()).isEqualTo("not_found");
    }
  }

  @Test
  void unknownAndMalformedIdsAreNotFound() {
    assertThat(api.get("/v1/orders/00000000-0000-0000-0000-000000000000").status()).isEqualTo(404);
    assertThat(api.get("/v1/orders/not-a-uuid").errorCode()).isEqualTo("not_found");
    assertThat(api.get("/v1/orders/1-1-1-1-1").status()).isEqualTo(404);
    assertThat(api.get("/v1/nothing").errorCode()).isEqualTo("not_found");
  }

  @Test
  void methodMediaTypeAndAcceptErrors() {
    assertThat(api.send("DELETE", "/v1/orders", null).errorCode()).isEqualTo("method_not_allowed");
    assertThat(api.post("/v1/orders", Api.orderBody(), "Content-Type", "text/plain").errorCode())
        .isEqualTo("unsupported_media_type");
    assertThat(api.post("/v1/orders", Api.orderBody(), "Accept", "text/csv").status())
        .isEqualTo(406);
  }

  /** Each row: a replacement applied to the valid body, and the expected status. */
  @ParameterizedTest(name = "{0}")
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
          empty object             | {}                                                  | 400
          not json                 | <<not json>>                                       | 400
          json array               | []                                                  | 400
          no items                 | "items":[]                                          | 400
          quantity zero            | "quantity":2 => "quantity":0                        | 400
          quantity negative        | "quantity":2 => "quantity":-1                       | 400
          quantity max             | "quantity":2 => "quantity":1000                     | 201
          quantity max plus one    | "quantity":2 => "quantity":1001                     | 400
          quantity as string       | "quantity":2 => "quantity":"2"                      | 400
          quantity as decimal      | "quantity":2 => "quantity":1.5                      | 400
          quantity huge            | "quantity":2 => "quantity":1000000000000            | 400
          unit amount zero         | "unit_amount":1000 => "unit_amount":0               | 400
          unit amount max          | "unit_amount":1000 => "unit_amount":99999999        | 400
          unit amount as money     | "unit_amount":1000 => "unit_amount":"10.00"         | 400
          lowercase currency       | "EUR" => "eur"                                      | 400
          unknown currency         | "EUR" => "ZZZ"                                      | 400
          non-spendable currency   | "EUR" => "XAU"                                      | 400
          zero-decimal currency    | "EUR" => "JPY"                                      | 201
          bad payment method       | "pm_card_ok" => "card 4242"                         | 400
          null payment method      | "pm_card_ok" => null                                | 400
          unknown field            | "currency":"EUR" => "currency":"EUR","total":1      | 400
          bad sku                  | "sku-1" => "sku 1"                                  | 400
          sku as number            | "sku-1" => 123                                      | 400
          sku as boolean           | "sku-1" => true                                     | 400
          currency as number       | "EUR" => 978                                        | 400
          payment method as float  | "pm_card_ok" => 1.5                                 | 400
          reference as number      | "currency":"EUR" => "currency":"EUR","merchant_reference":7 | 400
          long name                | Alice Example => <<5000 x>>                         | 400
          empty name               | Alice Example =>                                    | 400
          emoji rtl cjk name       | Alice Example => Ali 😀 مرحبا 你好                     | 201
          script tag name          | Alice Example => <script>alert(1)</script>          | 201
          quotes in name           | Alice Example => Al \\"Quote\\"                     | 201
          control char name        | Alice Example => Alice\\u0007                       | 400
          unknown country          | "NL" => "XX"                                        | 400
          lowercase country        | "NL" => "nl"                                        | 400
          bad postal code          | 12345 => 12<b>                                       | 400
          """)
  void validationMatrix(String name, String change, int status) {
    String body = apply(Api.orderBody(), change);
    Api.Response r = api.post("/v1/orders", body);
    assertThat(r.status()).as(name + ": " + r.body()).isEqualTo(status);
    if (status == 400) {
      assertThat(r.errorCode()).isEqualTo("invalid_argument");
      assertThat(r.json().at("/error/request_id").asString()).isNotBlank();
    }
  }

  /**
   * Each row: a refund body for a completed order of 2 x sku-1 at 10.00 and 1 x sku-2 at 5.00, the
   * expected status, and the expected amount (202) or error code.
   */
  @ParameterizedTest(name = "{0}")
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
          one minor unit            | {"amount":1}                                           | 202 | 1
          the whole capture         | {"amount":2500}                                        | 202 | 2500
          one unit over the capture | {"amount":2501}                                        | 409 | refund_exceeds_captured
          at the amount cap         | {"amount":99999999}                                    | 409 | refund_exceeds_captured
          over the amount cap       | {"amount":100000000}                                   | 400 | invalid_argument
          amount zero               | {"amount":0}                                           | 400 | invalid_argument
          amount negative           | {"amount":-1}                                          | 400 | invalid_argument
          amount as string          | {"amount":"100"}                                       | 400 | invalid_argument
          amount as money           | {"amount":"1.00"}                                      | 400 | invalid_argument
          amount as decimal         | {"amount":10.5}                                        | 400 | invalid_argument
          amount as exponent        | {"amount":1e3}                                         | 400 | invalid_argument
          amount as boolean         | {"amount":true}                                        | 400 | invalid_argument
          amount null               | {"amount":null}                                        | 400 | invalid_argument
          empty object              | {}                                                     | 400 | invalid_argument
          amount and lines          | {"amount":100,"lines":[{"sku":"sku-1","quantity":1}]}  | 400 | invalid_argument
          one line                  | {"lines":[{"sku":"sku-1","quantity":1}]}               | 202 | 1000
          two lines                 | {"lines":[{"sku":"sku-2","quantity":1},{"sku":"sku-1","quantity":2}]} | 202 | 2500
          more than was bought      | {"lines":[{"sku":"sku-1","quantity":3}]}               | 409 | refund_exceeds_purchased
          sku not on the order      | {"lines":[{"sku":"sku-9","quantity":1}]}               | 400 | invalid_argument
          sku twice                 | {"lines":[{"sku":"sku-1","quantity":1},{"sku":"sku-1","quantity":1}]} | 400 | invalid_argument
          no lines                  | {"lines":[]}                                           | 400 | invalid_argument
          lines null                | {"lines":null}                                         | 400 | invalid_argument
          lines not an array        | {"lines":{"sku":"sku-1","quantity":1}}                 | 400 | invalid_argument
          null line                 | {"lines":[null]}                                       | 400 | invalid_argument
          line without quantity     | {"lines":[{"sku":"sku-1"}]}                            | 400 | invalid_argument
          line without sku          | {"lines":[{"quantity":1}]}                             | 400 | invalid_argument
          quantity zero             | {"lines":[{"sku":"sku-1","quantity":0}]}               | 400 | invalid_argument
          quantity over the cap     | {"lines":[{"sku":"sku-1","quantity":1001}]}            | 400 | invalid_argument
          quantity as string        | {"lines":[{"sku":"sku-1","quantity":"1"}]}             | 400 | invalid_argument
          quantity as decimal       | {"lines":[{"sku":"sku-1","quantity":1.5}]}             | 400 | invalid_argument
          sku as number             | {"lines":[{"sku":1,"quantity":1}]}                     | 400 | invalid_argument
          bad sku                   | {"lines":[{"sku":"sku 1","quantity":1}]}               | 400 | invalid_argument
          unknown line field        | {"lines":[{"sku":"sku-1","quantity":1,"amount":5}]}    | 400 | invalid_argument
          unknown field             | {"amount":100,"reason":"damaged"}                      | 400 | invalid_argument
          json array                | []                                                     | 400 | invalid_argument
          not json                  | {"amount":                                             | 400 | invalid_argument
          """)
  void refundValidationMatrix(String name, String body, int status, String expected) {
    String id = create(Api.orderBody());
    awaitState(id, "COMPLETED");
    Api.Response r = api.post("/v1/orders/" + id + "/refunds", body);
    assertThat(r.status()).as(name + ": " + r.body()).isEqualTo(status);
    if (status == 202) {
      assertThat(r.json().get("amount").asLong()).isEqualTo(Long.parseLong(expected));
      assertThat(r.json().get("state").asString()).isEqualTo("PENDING");
      assertThat(r.json().get("order_id").asString()).isEqualTo(id);
    } else {
      assertThat(r.errorCode()).isEqualTo(expected);
      assertThat(r.json().at("/error/request_id").asString()).isNotBlank();
      assertThat(api.get("/v1/orders/" + id + "/refunds").json().get("data"))
          .as("a refused refund leaves nothing behind")
          .isEmpty();
    }
  }

  @Test
  void tooManyRefundLines() {
    String id = create(Api.orderBody());
    awaitState(id, "COMPLETED");
    String lines =
        IntStream.range(0, 51)
            .mapToObj(i -> "{\"sku\":\"s" + i + "\",\"quantity\":1}")
            .collect(Collectors.joining(","));
    Api.Response r = api.post("/v1/orders/" + id + "/refunds", "{\"lines\":[" + lines + "]}");
    assertThat(r.status()).isEqualTo(400);
    assertThat(r.json().at("/error/message").asString()).startsWith("lines:");
  }

  @Test
  void refundsBeforeCompletionAndOfOtherMerchantsOrdersAreRefused() {
    faults.set("inventory.reserve", new FaultPlan(0, 0, 0, Duration.ofMillis(800), List.of()));
    String id = create(Api.orderBody());
    for (String body :
        List.of("{\"amount\":100}", "{\"lines\":[{\"sku\":\"sku-1\",\"quantity\":1}]}")) {
      Api.Response early = api.post("/v1/orders/" + id + "/refunds", body);
      assertThat(early.status()).as(body).isEqualTo(409);
      assertThat(early.errorCode()).isEqualTo("order_not_refundable");
    }
    Api globex = api.as(TestKeys.GLOBEX);
    assertThat(globex.post("/v1/orders/" + id + "/refunds", "{\"amount\":100}").errorCode())
        .isEqualTo("not_found");
    assertThat(globex.get("/v1/orders/" + id + "/refunds").errorCode()).isEqualTo("not_found");
    assertThat(api.get("/v1/orders/not-a-uuid/refunds").errorCode()).isEqualTo("not_found");
    assertThat(api.get("/v1/orders/" + id + "/refunds").json().get("data")).isEmpty();
  }

  @Test
  void aTotalOverTheCapIsRefused() {
    String body =
        Api.orderBody()
            .replace("\"unit_amount\":1000", "\"unit_amount\":60000000")
            .replace("\"unit_amount\":500", "\"unit_amount\":50000000");
    Api.Response r = api.post("/v1/orders", body);
    assertThat(r.status()).isEqualTo(400);
    assertThat(r.json().at("/error/message").asString()).contains("at most 99999999");
  }

  @Test
  void tooManyItems() {
    String items =
        IntStream.range(0, 51)
            .mapToObj(i -> "{\"sku\":\"s" + i + "\",\"quantity\":1,\"unit_amount\":1}")
            .collect(Collectors.joining(","));
    String body = Api.orderBody().replaceFirst("\"items\":\\[[^]]*]", "\"items\":[" + items + "]");
    Api.Response r = api.post("/v1/orders", body);
    assertThat(r.status()).isEqualTo(400);
    assertThat(r.json().at("/error/message").asString()).startsWith("items:");
  }

  @Test
  void oversizedBodiesAreRefusedWithOrWithoutALength() throws Exception {
    String big = Api.orderBody().replace("Alice Example", "x".repeat(70_000));
    Api.Response declared = api.post("/v1/orders", big);
    assertThat(declared.status()).isEqualTo(413);
    assertThat(declared.errorCode()).isEqualTo("payload_too_large");

    HttpRequest chunked =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/orders"))
            .header("Authorization", "Bearer " + TestKeys.ACME)
            .header("Content-Type", "application/json")
            .header("Idempotency-Key", "chunked-too-big")
            .POST(
                HttpRequest.BodyPublishers.ofInputStream(
                    () ->
                        new java.io.ByteArrayInputStream(
                            big.getBytes(java.nio.charset.StandardCharsets.UTF_8))))
            .build();
    HttpResponse<String> r =
        HttpClient.newHttpClient().send(chunked, HttpResponse.BodyHandlers.ofString());
    assertThat(r.statusCode()).isEqualTo(413);
    assertThat(r.body()).contains("payload_too_large");
  }

  private static String apply(String body, String change) {
    String c = change.trim();
    if (c.equals("{}") || c.equals("[]")) {
      return c;
    }
    if (c.equals("<<not json>>")) {
      return "{\"items\":";
    }
    if (c.startsWith("\"items\":[]")) {
      return body.replaceFirst("\"items\":\\[[^]]*]", "\"items\":[]");
    }
    String[] parts = c.split("=>", -1);
    String from = parts[0].trim();
    String to = parts[1].trim().replace("<<5000 x>>", "x".repeat(5000));
    return body.replace(from, to);
  }
}
