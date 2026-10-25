package io.github.laiwf94.payflow.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.laiwf94.payflow.sim.SimulatorKey;
import io.github.laiwf94.payflow.support.Api;
import io.github.laiwf94.payflow.support.TestKeys;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.test.annotation.DirtiesContext;

/** Logs, metrics, health and what must never appear in logs, on the running application. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "payflow.saga.enabled=true",
      "payflow.outbox.enabled=true",
      "payflow.timeline.enabled=true",
      "logging.level.io.github.laiwf94.payflow.timeline=DEBUG"
    })
@DirtiesContext
// Spring Boot turns metric exporters off in tests unless asked.
@AutoConfigureObservability
@ExtendWith(OutputCaptureExtension.class)
class ObservabilityTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @LocalServerPort int port;
  @LocalManagementPort int managementPort;
  @Autowired SimulatorKey simulatorKey;
  @Autowired Environment env;

  private List<JsonNode> logLines(CapturedOutput output) {
    List<JsonNode> out = new ArrayList<>();
    for (String line : output.getAll().lines().toList()) {
      if (line.startsWith("{")) {
        try {
          out.add(JSON.readTree(line));
        } catch (Exception e) {
          // A line from another writer interleaved with ours; not a log record.
        }
      }
    }
    return out;
  }

  @Test
  void oneCorrelationIdFollowsTheOrderFromRequestToConsumer(CapturedOutput output) {
    Api api = new Api(port, TestKeys.ACME);
    String id =
        api.post("/v1/orders", Api.orderBody(), "X-Request-Id", "trace-0001")
            .json()
            .get("id")
            .asText();
    await()
        .atMost(Duration.ofSeconds(30))
        .until(() -> api.get("/v1/orders/" + id + "/events").json().get("data").size() == 5);
    await()
        .atMost(Duration.ofSeconds(10))
        .until(
            () ->
                logLines(output).stream()
                    .anyMatch(
                        l ->
                            l.path("logger_name").asText().endsWith("TimelineConsumer")
                                && l.path("message").asText().contains("order.completed")));

    List<JsonNode> traced =
        logLines(output).stream()
            .filter(l -> "trace-0001".equals(l.path("correlation_id").asText()))
            .toList();
    Set<String> loggers =
        traced.stream()
            .map(l -> l.path("logger_name").asText())
            .map(n -> n.substring(n.lastIndexOf('.') + 1))
            .collect(Collectors.toSet());
    assertThat(loggers).contains("OrderService", "SagaWorker", "TimelineConsumer");
    assertThat(traced)
        .filteredOn(l -> l.path("logger_name").asText().endsWith("SagaWorker"))
        .allMatch(l -> id.equals(l.path("order_id").asText()));
    assertThat(traced)
        .filteredOn(l -> l.path("logger_name").asText().endsWith("OrderService"))
        .allMatch(l -> "acme".equals(l.path("merchant_id").asText()));

    String all = output.getAll();
    assertThat(all).doesNotContain(TestKeys.ACME);
    assertThat(all).doesNotContain(simulatorKey.value());
    assertThat(all).doesNotContain("Bearer ");

    String metrics = new Api(managementPort, null).get("/actuator/prometheus").body();
    assertThat(metrics)
        .contains("payflow_saga_transitions_total{")
        .contains("payflow_saga_step_seconds_count{")
        .contains("payflow_outbox_published_total")
        .contains("payflow_outbox_pending")
        .contains("payflow_idempotency_requests_total{")
        .contains("http_server_requests_seconds_count{");
  }

  @Test
  void healthIsPublicButEverythingElseIsOnTheLoopbackManagementPort() {
    Api main = new Api(port, null);
    assertThat(main.get("/livez").status()).isEqualTo(200);
    assertThat(main.get("/readyz").status()).isEqualTo(200);
    assertThat(main.get("/readyz").body()).isEqualTo("{\"status\":\"UP\"}");
    assertThat(main.get("/actuator/prometheus").status()).isEqualTo(404);
    assertThat(main.get("/actuator/health").status()).isEqualTo(404);

    Api management = new Api(managementPort, null);
    assertThat(management.get("/actuator/health").json().get("status").asText()).isEqualTo("UP");
    assertThat(management.get("/actuator/health").json().has("components")).isFalse();
    assertThat(management.get("/actuator/prometheus").status()).isEqualTo(200);
    for (String hidden :
        List.of("/actuator/env", "/actuator/beans", "/actuator/heapdump", "/actuator/loggers")) {
      assertThat(management.get(hidden).status()).as(hidden).isEqualTo(404);
    }
    assertThat(env.getProperty("management.server.address")).isEqualTo("127.0.0.1");
  }
}
