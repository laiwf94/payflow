package io.github.laiwf94.payflow.sim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.laiwf94.payflow.support.TestInfrastructure;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "payflow.simulator.key=" + SimulatorHttpTest.KEY,
      "payflow.simulator.answer-delay=800ms"
    })
@Import(TestInfrastructure.class)
class SimulatorHttpTest {

  static final String KEY = "test-simulator-key-0123456789";

  @LocalServerPort int port;
  @Autowired PaymentProviderSimulator payments;
  @Autowired InventorySimulator inventory;

  private final HttpClient http = HttpClient.newHttpClient();

  @BeforeEach
  void reset() throws Exception {
    assertThat(send("POST", "/sim/control/reset", null).statusCode()).isEqualTo(204);
  }

  @Test
  void requiresTheKey() throws Exception {
    HttpResponse<String> r =
        http.send(
            HttpRequest.newBuilder(uri("/sim/payments?reference=x")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(r.statusCode()).isEqualTo(401);
    HttpResponse<String> wrong =
        http.send(
            HttpRequest.newBuilder(uri("/sim/payments?reference=x"))
                .header("Authorization", "Bearer wrong-key-0123456789")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(wrong.statusCode()).isEqualTo(401);
  }

  @Test
  void paymentLifecycleOverHttp() throws Exception {
    HttpResponse<String> auth =
        send(
            "POST",
            "/sim/payments/authorizations",
            "{\"reference\":\"o1\",\"amount\":2500,\"currency\":\"EUR\",\"payment_method\":\"pm_card_ok\"}",
            "Idempotency-Key",
            "auth-o1");
    assertThat(auth.statusCode()).isEqualTo(201);
    assertThat(auth.body()).contains("\"status\":\"AUTHORIZED\"").contains("pay_00000001");
    assertThat(send("POST", "/sim/payments/pay_00000001/capture", "{\"amount\":2500}").statusCode())
        .isEqualTo(200);
    assertThat(send("POST", "/sim/payments/pay_00000001/refunds", "{\"amount\":2500}").body())
        .contains("\"refunded\":2500");
    assertThat(send("POST", "/sim/payments/pay_00000001/void", null).statusCode()).isEqualTo(409);
    assertThat(send("GET", "/sim/payments?reference=o1", null).body()).contains("pay_00000001");
    assertThat(send("GET", "/sim/control/ledger?reference=o1", null).body())
        .contains("\"operation\":\"refund\"");
  }

  @Test
  void declineIs402WithTheCode() throws Exception {
    HttpResponse<String> r =
        send(
            "POST",
            "/sim/payments/authorizations",
            "{\"reference\":\"o1\",\"amount\":1,\"currency\":\"EUR\",\"payment_method\":\"pm_card_declined\"}");
    assertThat(r.statusCode()).isEqualTo(402);
    assertThat(r.body()).contains("\"decline_code\":\"card_declined\"");
  }

  @Test
  void anUnansweredCallTimesOutTheClientButIsApplied() throws Exception {
    assertThat(
            send("PUT", "/sim/control/faults/payment.authorize", "{\"script\":[\"unknown_after\"]}")
                .statusCode())
        .isEqualTo(200);
    HttpRequest req =
        request(
                "POST",
                "/sim/payments/authorizations",
                "{\"reference\":\"o1\",\"amount\":10,\"currency\":\"EUR\",\"payment_method\":\"pm_card_ok\"}")
            .timeout(Duration.ofMillis(300))
            .build();
    assertThatThrownBy(() -> http.send(req, HttpResponse.BodyHandlers.ofString()))
        .isInstanceOf(HttpTimeoutException.class);
    assertThat(payments.paymentsFor("o1")).hasSize(1);
  }

  @Test
  void errorFaultIs503WithNoEffect() throws Exception {
    send("PUT", "/sim/control/faults/inventory.reserve", "{\"error_rate\":1.0,\"latency_ms\":20}");
    HttpResponse<String> r =
        send(
            "POST",
            "/sim/inventory/reservations",
            "{\"reservation_id\":\"o1\",\"lines\":[{\"sku\":\"a\",\"quantity\":1}]}");
    assertThat(r.statusCode()).isEqualTo(503);
    assertThat(inventory.reservation("o1")).isEmpty();
    assertThat(send("DELETE", "/sim/control/faults", null).statusCode()).isEqualTo(204);
  }

  @Test
  void inventoryAndShippingOverHttp() throws Exception {
    assertThat(send("PUT", "/sim/control/stock/a", "{\"available\":1}").body())
        .contains("\"available\":1");
    String body = "{\"reservation_id\":\"o1\",\"lines\":[{\"sku\":\"a\",\"quantity\":1}]}";
    assertThat(send("POST", "/sim/inventory/reservations", body).statusCode()).isEqualTo(201);
    assertThat(send("GET", "/sim/inventory/reservations/o1", null).statusCode()).isEqualTo(200);
    HttpResponse<String> oos =
        send(
            "POST",
            "/sim/inventory/reservations",
            "{\"reservation_id\":\"o2\",\"lines\":[{\"sku\":\"a\",\"quantity\":1}]}");
    assertThat(oos.statusCode()).isEqualTo(409);
    assertThat(oos.body()).contains("out_of_stock");
    assertThat(send("DELETE", "/sim/inventory/reservations/o1", null).statusCode()).isEqualTo(204);
    assertThat(send("GET", "/sim/inventory/reservations/o1", null).statusCode()).isEqualTo(404);

    HttpResponse<String> shp =
        send(
            "POST",
            "/sim/shipping/shipments",
            "{\"reference\":\"o1\",\"postal_code\":\"12345\",\"country\":\"NL\"}");
    assertThat(shp.statusCode()).isEqualTo(201);
    assertThat(send("GET", "/sim/shipping/shipments?reference=o1", null).body())
        .contains("shp_00000001");
    assertThat(send("POST", "/sim/shipping/shipments/shp_00000001/cancel", null).body())
        .contains("CANCELLED");
    assertThat(
            send(
                    "POST",
                    "/sim/shipping/shipments",
                    "{\"reference\":\"o9\",\"postal_code\":\"00000\",\"country\":\"NL\"}")
                .statusCode())
        .isEqualTo(422);
  }

  @Test
  void controlValidatesInput() throws Exception {
    assertThat(send("PUT", "/sim/control/faults/payment.nope", "{}").statusCode()).isEqualTo(404);
    assertThat(
            send("PUT", "/sim/control/faults/payment.capture", "{\"error_rate\":2}").statusCode())
        .isEqualTo(400);
    assertThat(
            send("PUT", "/sim/control/faults/payment.capture", "{\"script\":[\"explode\"]}")
                .statusCode())
        .isEqualTo(400);
    assertThat(send("PUT", "/sim/control/stock/a", "{\"available\":-1}").statusCode())
        .isEqualTo(400);
    assertThat(send("PUT", "/sim/control/settings", "{\"answer_delay_ms\":-5}").statusCode())
        .isEqualTo(400);
    assertThat(send("PUT", "/sim/control/settings", "{not json").statusCode()).isEqualTo(400);
    assertThat(send("GET", "/sim/control/ledger", null).statusCode()).isEqualTo(400);
    HttpResponse<String> s =
        send(
            "PUT",
            "/sim/control/settings",
            "{\"honor_idempotency_keys\":false,\"answer_delay_ms\":900}");
    assertThat(s.body()).contains("\"honor_idempotency_keys\":false").contains("900");
  }

  private HttpResponse<String> send(String method, String path, String body, String... headers)
      throws IOException, InterruptedException {
    HttpRequest.Builder b = request(method, path, body);
    for (int i = 0; i < headers.length; i += 2) {
      b.header(headers[i], headers[i + 1]);
    }
    return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpRequest.Builder request(String method, String path, String body) {
    return HttpRequest.newBuilder(uri(path))
        .header("Authorization", "Bearer " + KEY)
        .header("Content-Type", "application/json")
        .method(
            method,
            body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
  }

  private URI uri(String path) {
    return URI.create("http://localhost:" + port + path);
  }
}
