package io.github.laiwf94.payflow.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.laiwf94.payflow.idempotency.IdempotencyFilter;
import io.github.laiwf94.payflow.saga.SagaWorker;
import io.github.laiwf94.payflow.support.Api;
import io.github.laiwf94.payflow.support.Db;
import io.github.laiwf94.payflow.support.TestKeys;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.yaml.snakeyaml.Yaml;

/**
 * The served OpenAPI document against the running application: the same routes in both, every
 * documented response produced by a real request, and every body valid against its schema.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenApiContractTest {

  @LocalServerPort int port;

  @Autowired
  @Qualifier("requestMappingHandlerMapping")
  RequestMappingHandlerMapping mappings;

  @Autowired SagaWorker worker;
  @Autowired FilterRegistrationBean<IdempotencyFilter> idempotency;
  @Autowired JdbcClient jdbc;

  private Map<String, Object> spec;
  private SchemaValidator validator;
  private Api api;
  private final Set<String> exercised = new HashSet<>();

  @BeforeEach
  void setUp() throws Exception {
    Db.truncateAll(jdbc);
    try (InputStream in = getClass().getResourceAsStream("/static/openapi.yaml")) {
      spec = new Yaml().load(in);
    }
    validator = new SchemaValidator(spec);
    api = new Api(port, TestKeys.ACME);
  }

  @Test
  void theSpecIsServedWithoutAuthentication() {
    Api.Response r = new Api(port, null).get("/openapi.yaml");
    assertThat(r.status()).isEqualTo(200);
    assertThat(r.body()).startsWith("openapi: 3.1.0");
  }

  @Test
  void specAndRoutesMatchBothWays() {
    Set<String> routes = new TreeSet<>();
    for (RequestMappingInfo info : mappings.getHandlerMethods().keySet()) {
      for (String pattern : info.getPathPatternsCondition().getPatternValues()) {
        if (pattern.startsWith("/v1/")) {
          info.getMethodsCondition()
              .getMethods()
              .forEach(m -> routes.add(m.name() + " " + pattern));
        }
      }
    }
    assertThat(routes).isEqualTo(documentedRoutes());
  }

  @Test
  void everyDocumentedResponseIsProducedAndMatchesItsSchema() {
    String pending = createdOrderId();
    record("POST /v1/orders", api.post("/v1/orders", Api.orderBody()));
    record(
        "POST /v1/orders", api.post("/v1/orders", Api.orderBody().replace("\"EUR\"", "\"eur\"")));
    record("POST /v1/orders", new Api(port, null).post("/v1/orders", Api.orderBody()));
    jdbc.sql(
            """
            INSERT INTO idempotency_keys (merchant_id, idem_key, request_method, request_path,
                request_hash, lock_token, locked_until, created_at)
            VALUES ('acme', 'busy', 'POST', '/v1/orders', :hash, gen_random_uuid(),
                now() + interval '1 minute', now())
            """)
        .param(
            "hash",
            idempotency
                .getFilter()
                .hash(
                    "POST",
                    "/v1/orders",
                    Api.orderBody().getBytes(java.nio.charset.StandardCharsets.UTF_8)))
        .update();
    record("POST /v1/orders", api.post("/v1/orders", Api.orderBody(), "Idempotency-Key", "busy"));
    record(
        "POST /v1/orders",
        api.post("/v1/orders", Api.orderBody().replace("Alice", "x".repeat(70_000))));
    record(
        "POST /v1/orders", api.post("/v1/orders", "<order/>", "Content-Type", "application/xml"));
    api.post("/v1/orders", Api.orderBody(), "Idempotency-Key", "reused");
    record(
        "POST /v1/orders",
        api.post("/v1/orders", Api.orderBody("pm_card_expired", "1"), "Idempotency-Key", "reused"));

    String missing = "00000000-0000-0000-0000-000000000000";
    record("GET /v1/orders/{id}", api.get("/v1/orders/" + pending));
    record("GET /v1/orders/{id}", api.get("/v1/orders/" + missing));
    record("GET /v1/orders/{id}", new Api(port, null).get("/v1/orders/" + pending));

    record("GET /v1/orders/{id}/events", api.get("/v1/orders/" + pending + "/events"));
    record("GET /v1/orders/{id}/events", api.get("/v1/orders/" + missing + "/events"));
    record(
        "GET /v1/orders/{id}/events", new Api(port, null).get("/v1/orders/" + missing + "/events"));

    record("POST /v1/orders/{id}/refund", api.post("/v1/orders/" + pending + "/refund", null));
    record(
        "POST /v1/orders/{id}/refund",
        api.post("/v1/orders/" + pending + "/refund", null, "Idempotency-Key", "has space"));
    record("POST /v1/orders/{id}/refund", api.post("/v1/orders/" + missing + "/refund", null));
    record(
        "POST /v1/orders/{id}/refund",
        new Api(port, null).post("/v1/orders/" + pending + "/refund", null));

    record("POST /v1/orders/{id}/cancel", api.post("/v1/orders/" + pending + "/cancel", null));
    record(
        "POST /v1/orders/{id}/cancel",
        api.post("/v1/orders/" + pending + "/cancel", null, "Idempotency-Key", "has space"));
    record("POST /v1/orders/{id}/cancel", api.post("/v1/orders/" + missing + "/cancel", null));
    record(
        "POST /v1/orders/{id}/cancel",
        new Api(port, null).post("/v1/orders/" + pending + "/cancel", null));

    String done = createdOrderId();
    while (worker.runOnce() > 0) {
      Thread.onSpinWait();
    }
    record("POST /v1/orders/{id}/cancel", api.post("/v1/orders/" + done + "/cancel", null));
    record("POST /v1/orders/{id}/refund", api.post("/v1/orders/" + done + "/refund", null));

    assertThat(exercised).isEqualTo(documentedResponses());
  }

  private String createdOrderId() {
    return api.post("/v1/orders", Api.orderBody()).json().get("id").asText();
  }

  /** Validates the response against the spec and notes which documented response it was. */
  @SuppressWarnings("unchecked")
  private void record(String operation, Api.Response r) {
    String[] parts = operation.split(" ", 2);
    Map<String, Object> op =
        (Map<String, Object>)
            ((Map<String, Object>) ((Map<String, Object>) spec.get("paths")).get(parts[1]))
                .get(parts[0].toLowerCase(java.util.Locale.ROOT));
    Map<String, Object> responses = (Map<String, Object>) op.get("responses");
    String status = String.valueOf(r.status());
    assertThat(responses)
        .as(operation + " documents " + status + ": " + r.body())
        .containsKey(status);
    Object response = responses.get(status);
    if (((Map<String, Object>) response).containsKey("$ref")) {
      response = validator.resolve((String) ((Map<String, Object>) response).get("$ref"));
    }
    Object schema =
        ((Map<String, Object>)
                ((Map<String, Object>) ((Map<String, Object>) response).get("content"))
                    .get("application/json"))
            .get("schema");
    assertThat(r.json()).as(operation + " " + status + " has a JSON body").isNotNull();
    assertThat(validator.validate(r.json(), schema)).as(operation + " " + status).isEmpty();
    exercised.add(operation + " " + status);
  }

  @SuppressWarnings("unchecked")
  private Set<String> documentedRoutes() {
    Set<String> out = new TreeSet<>();
    ((Map<String, Object>) spec.get("paths"))
        .forEach(
            (path, ops) ->
                ((Map<String, Object>) ops)
                    .keySet()
                    .forEach(m -> out.add(m.toUpperCase(java.util.Locale.ROOT) + " " + path)));
    return out;
  }

  @SuppressWarnings("unchecked")
  private Set<String> documentedResponses() {
    Set<String> out = new TreeSet<>();
    ((Map<String, Object>) spec.get("paths"))
        .forEach(
            (path, ops) ->
                ((Map<String, Object>) ops)
                    .forEach(
                        (m, op) ->
                            ((Map<String, Object>) ((Map<String, Object>) op).get("responses"))
                                .keySet()
                                .forEach(
                                    s ->
                                        out.add(
                                            m.toUpperCase(java.util.Locale.ROOT)
                                                + " "
                                                + path
                                                + " "
                                                + s))));
    return out;
  }

  @Test
  void theValidatorCatchesWhatItShould() {
    Object order = Map.of("$ref", "#/components/schemas/Error");
    var json = new com.fasterxml.jackson.databind.ObjectMapper();
    assertThat(
            validator.validate(
                json.valueToTree(Map.of("error", Map.of("code", "x", "message", "y"))), order))
        .containsExactly("$.error: missing required request_id");
    assertThat(validator.validate(json.valueToTree(List.of()), order))
        .singleElement()
        .asString()
        .contains("expected object");
    assertThat(
            validator.validate(
                json.valueToTree(
                    Map.of(
                        "error", Map.of("code", "x", "message", "y", "request_id", "r", "z", 1))),
                order))
        .containsExactly("$.error: unexpected property z");
  }
}
