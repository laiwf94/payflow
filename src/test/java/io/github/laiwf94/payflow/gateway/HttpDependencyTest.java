package io.github.laiwf94.payflow.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.github.laiwf94.payflow.money.Money;
import io.github.laiwf94.payflow.sim.SimulatorKey;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/** Outcome classification against a server that answers whatever the test wants. */
class HttpDependencyTest {

  private HttpServer server;
  private final AtomicReference<Integer> status = new AtomicReference<>(200);
  private final AtomicReference<String> body = new AtomicReference<>("{}");
  private final AtomicReference<String> seenAuth = new AtomicReference<>();

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/",
        ex -> {
          seenAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
          byte[] b = body.get().getBytes(StandardCharsets.UTF_8);
          ex.sendResponseHeaders(status.get(), b.length == 0 ? -1 : b.length);
          if (b.length > 0) {
            ex.getResponseBody().write(b);
          }
          ex.close();
        });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private HttpPaymentGateway gateway(String base) {
    return new HttpPaymentGateway(
        new HttpDependency(
            "payment",
            () -> base,
            () -> "secret-key",
            Duration.ofMillis(300),
            Duration.ofMillis(500),
            RestClient.builder(),
            new ObjectMapper()));
  }

  private String base() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @ParameterizedTest
  @CsvSource({
    "429, Transient",
    "503, Transient",
    "401, Transient",
    "403, Transient",
    "500, Unknown",
    "502, Unknown",
    "504, Unknown",
    "408, Unknown",
    "302, Unknown",
    "409, Rejected",
    "400, Rejected"
  })
  void classifiesStatuses(int code, String kind) {
    status.set(code);
    body.set("{\"error\":{\"code\":\"x\",\"message\":\"y\"}}");
    CallResult<PaymentInfo> r = gateway(base()).capture("k", "pay_1", 1);
    assertThat(r.getClass().getSimpleName()).isEqualTo(kind);
    assertThat(seenAuth.get()).isEqualTo("Bearer secret-key");
  }

  @Test
  void anOversizedAnswerIsNotBufferedAndCountsAsUnknown() {
    status.set(200);
    body.set("{\"id\":\"" + "x".repeat(HttpDependency.MAX_RESPONSE_BYTES) + "\"}");
    assertThat(gateway(base()).capture("k", "p", 1))
        .isEqualTo(new CallResult.Unknown<>("payment sent an oversized response"));
  }

  @Test
  void unreadableBodies() {
    status.set(200);
    body.set("not json");
    assertThat(gateway(base()).capture("k", "p", 1)).isInstanceOf(CallResult.Unknown.class);
    status.set(409);
    assertThat(gateway(base()).capture("k", "p", 1))
        .isEqualTo(new CallResult.Rejected<>("unreadable_error", "payment returned 409"));
    status.set(404);
    body.set("");
    assertThat(gateway(base()).findByReference("o"))
        .isEqualTo(new CallResult.Rejected<>("lookup_failed", ""));
  }

  @Test
  void aBodyOfTheWrongShapeIsAnError() {
    status.set(200);
    body.set("{\"amount\":\"lots\"}");
    assertThatThrownBy(() -> gateway(base()).capture("k", "p", 1))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void aDeclineWithoutACodeFallsBack() {
    status.set(402);
    body.set("{}");
    assertThat(gateway(base()).authorize("k", "o", Money.of(1, "EUR"), "pm"))
        .isEqualTo(new CallResult.Rejected<>("card_declined", ""));
  }

  @Test
  void refusedConnectionIsTransient() throws IOException {
    int port;
    try (ServerSocket s = new ServerSocket(0)) {
      port = s.getLocalPort();
    }
    assertThat(gateway("http://127.0.0.1:" + port).capture("k", "p", 1))
        .isInstanceOf(CallResult.Transient.class);
  }

  @Test
  void slowAnswerIsUnknown() {
    server.createContext(
        "/slow",
        ex -> {
          try {
            Thread.sleep(1500);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          ex.sendResponseHeaders(200, -1);
          ex.close();
        });
    assertThat(gateway(base() + "/slow").capture("k", "p", 1))
        .isInstanceOf(CallResult.Unknown.class);
  }

  @Test
  void baseUrlValidation() {
    assertThat(GatewayConfig.validated("p", "https://pay.example.test/v1/"))
        .isEqualTo("https://pay.example.test/v1");
    for (String bad :
        new String[] {
          "ftp://x",
          "http://user:pw@host",
          "http://host/?q=1",
          "http://host/#f",
          "not a uri",
          "/rel"
        }) {
      assertThatThrownBy(() -> GatewayConfig.validated("p", bad))
          .as(bad)
          .isInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  void configRequiresUrlAndKeyWithoutTheSimulator() {
    var none = new DefaultListableBeanFactory().getBeanProvider(SimulatorKey.class);
    var port = new GatewayConfig.LocalPort();
    assertThatThrownBy(
            () ->
                GatewayConfig.dependency(
                    "payment",
                    "/sim",
                    new DependencyProperties.Endpoint("", "", Duration.ZERO, Duration.ZERO),
                    port,
                    none,
                    RestClient.builder(),
                    new ObjectMapper()))
        .hasMessageContaining("base-url is required");
    assertThatThrownBy(
            () ->
                GatewayConfig.dependency(
                    "payment",
                    "/sim",
                    new DependencyProperties.Endpoint(
                        "http://pay.test", "", Duration.ofSeconds(1), Duration.ofSeconds(1)),
                    port,
                    none,
                    RestClient.builder(),
                    new ObjectMapper()))
        .hasMessageContaining("api-key is required");
    assertThat(
            GatewayConfig.dependency(
                "payment",
                "/sim",
                new DependencyProperties.Endpoint(
                    "http://pay.test", "k", Duration.ofSeconds(1), Duration.ofSeconds(1)),
                port,
                none,
                RestClient.builder(),
                new ObjectMapper()))
        .isNotNull();
    assertThatThrownBy(port::get).isInstanceOf(IllegalStateException.class);
  }
}
