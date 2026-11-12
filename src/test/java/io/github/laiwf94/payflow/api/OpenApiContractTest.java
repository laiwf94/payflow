package io.github.laiwf94.payflow.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.util.TreeMap;
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
 * documented response and error code produced by a real request, every body and header valid
 * against the spec, and the spec's error codes exactly the ones {@link ApiError} defines.
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
  @Autowired io.github.laiwf94.payflow.sim.Faults faults;

  private Map<String, Object> spec;
  private SchemaValidator validator;
  private Api api;
  private final Set<String> exercised = new HashSet<>();

  @BeforeEach
  void setUp() throws Exception {
    Db.truncateAll(jdbc);
    // The context, and with it the simulators, is shared with other test classes.
    faults.reset(new java.util.SplittableRandom(1));
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
  void everyDocumentedResponseAndErrorCodeIsProducedAndMatchesItsSchema() {
    Api anonymous = new Api(port, null);
    String pending = createdOrderId();
    String missing = "00000000-0000-0000-0000-000000000000";
    String tooLarge = Api.orderBody().replace("Alice", "x".repeat(70_000));

    // POST /v1/orders
    record("POST /v1/orders", api.post("/v1/orders", Api.orderBody()));
    replayed("POST /v1/orders", 201, "/v1/orders", Api.orderBody());
    record(
        "POST /v1/orders", api.post("/v1/orders", Api.orderBody().replace("\"EUR\"", "\"eur\"")));
    replayed("POST /v1/orders", 400, "/v1/orders", Api.orderBody().replace("\"EUR\"", "\"eur\""));
    record(
        "POST /v1/orders", api.post("/v1/orders", Api.orderBody(), "Idempotency-Key", "has space"));
    record("POST /v1/orders", anonymous.post("/v1/orders", Api.orderBody()));
    busyKey("busy-create", "/v1/orders", Api.orderBody());
    record(
        "POST /v1/orders",
        api.post("/v1/orders", Api.orderBody(), "Idempotency-Key", "busy-create"));
    String reference = "contract-" + java.util.UUID.randomUUID();
    record("POST /v1/orders", api.post("/v1/orders", Api.orderBody(reference)));
    record("POST /v1/orders", api.post("/v1/orders", Api.orderBody(reference)));
    replayed("POST /v1/orders", 409, "/v1/orders", Api.orderBody(reference));
    record("POST /v1/orders", api.post("/v1/orders", tooLarge));
    record(
        "POST /v1/orders", api.post("/v1/orders", "<order/>", "Content-Type", "application/xml"));
    replayed("POST /v1/orders", 415, "/v1/orders", "<order/>", "Content-Type", "application/xml");
    api.post("/v1/orders", Api.orderBody(), "Idempotency-Key", "reused");
    record(
        "POST /v1/orders",
        api.post("/v1/orders", Api.orderBody("pm_card_expired", "1"), "Idempotency-Key", "reused"));

    // GET /v1/orders
    Api.Response firstPage = api.get("/v1/orders?limit=1");
    record("GET /v1/orders", firstPage);
    record(
        "GET /v1/orders",
        api.get("/v1/orders?limit=1&cursor=" + firstPage.json().get("next_cursor").asString()));
    record("GET /v1/orders", api.get("/v1/orders?merchant_reference=" + reference));
    record("GET /v1/orders", api.get("/v1/orders?limit=0"));
    record("GET /v1/orders", api.get("/v1/orders?cursor=forged"));
    record("GET /v1/orders", anonymous.get("/v1/orders"));

    // GET /v1/orders/{id} and its events
    record("GET /v1/orders/{id}", api.get("/v1/orders/" + pending));
    record("GET /v1/orders/{id}", api.get("/v1/orders/" + missing));
    record("GET /v1/orders/{id}", anonymous.get("/v1/orders/" + pending));
    record("GET /v1/orders/{id}/events", api.get("/v1/orders/" + pending + "/events"));
    record("GET /v1/orders/{id}/events", api.get("/v1/orders/" + missing + "/events"));
    record("GET /v1/orders/{id}/events", anonymous.get("/v1/orders/" + missing + "/events"));

    // POST /v1/orders/{id}/refund and /cancel, on an order that hasn't been captured
    for (String action : List.of("refund", "cancel")) {
      String op = "POST /v1/orders/{id}/" + action;
      String path = "/v1/orders/" + pending + "/" + action;
      record(op, api.post(path, null, "Idempotency-Key", "has space"));
      record(op, anonymous.post(path, null));
      record(op, api.post("/v1/orders/" + missing + "/" + action, null));
      replayed(op, 404, "/v1/orders/" + missing + "/" + action, null);
      record(op, api.post(path, tooLarge));
      busyKey("busy-" + action, path, null);
      record(op, api.post(path, null, "Idempotency-Key", "busy-" + action));
      // One key on two different orders: the second is a different request.
      api.post(
          "/v1/orders/" + createdOrderId() + "/" + action,
          null,
          "Idempotency-Key",
          "reused-" + action);
      record(
          op,
          api.post(
              "/v1/orders/" + createdOrderId() + "/" + action,
              null,
              "Idempotency-Key",
              "reused-" + action));
    }
    record("POST /v1/orders/{id}/refund", api.post("/v1/orders/" + pending + "/refund", null));
    record("POST /v1/orders/{id}/cancel", api.post("/v1/orders/" + pending + "/cancel", null));
    replayed("POST /v1/orders/{id}/cancel", 202, "/v1/orders/" + pending + "/cancel", null);

    String done = createdOrderId();
    while (worker.runOnce() > 0) {
      Thread.onSpinWait();
    }
    assertThat(api.get("/v1/orders/" + done).json().get("state").asString()).isEqualTo("COMPLETED");
    record("POST /v1/orders/{id}/cancel", api.post("/v1/orders/" + done + "/cancel", null));
    record("POST /v1/orders/{id}/refund", api.post("/v1/orders/" + done + "/refund", null));
    replayed("POST /v1/orders/{id}/refund", 202, "/v1/orders/" + done + "/refund", null);

    // POST and GET /v1/orders/{id}/refunds
    String refundable = createdOrderId();
    while (worker.runOnce() > 0) {
      Thread.onSpinWait();
    }
    String op = "POST /v1/orders/{id}/refunds";
    String refunds = "/v1/orders/" + refundable + "/refunds";
    String amount = "{\"amount\":100}";
    record(op, api.post(refunds, amount));
    replayed(op, 202, refunds, "{\"lines\":[{\"sku\":\"sku-2\",\"quantity\":1}]}");
    record(op, api.post(refunds, "{\"amount\":0}"));
    replayed(op, 400, refunds, "{\"amount\":100,\"lines\":[]}");
    record(op, api.post(refunds, amount, "Idempotency-Key", "has space"));
    record(op, anonymous.post(refunds, amount));
    record(op, api.post("/v1/orders/" + missing + "/refunds", amount));
    replayed(op, 404, "/v1/orders/" + missing + "/refunds", amount);
    record(op, api.post("/v1/orders/" + pending + "/refunds", amount));
    record(op, api.post(refunds, "{\"amount\":2500}"));
    replayed(op, 409, refunds, "{\"amount\":2500}");
    record(op, api.post(refunds, "{\"lines\":[{\"sku\":\"sku-2\",\"quantity\":1}]}"));
    busyKey("busy-refunds", refunds, amount);
    record(op, api.post(refunds, amount, "Idempotency-Key", "busy-refunds"));
    record(op, api.post(refunds, tooLarge));
    record(op, api.post(refunds, "<refund/>", "Content-Type", "application/xml"));
    api.post(refunds, amount, "Idempotency-Key", "reused-refunds");
    record(op, api.post(refunds, "{\"amount\":101}", "Idempotency-Key", "reused-refunds"));
    record("GET /v1/orders/{id}/refunds", api.get(refunds));
    record("GET /v1/orders/{id}/refunds", api.get("/v1/orders/" + missing + "/refunds"));
    record("GET /v1/orders/{id}/refunds", anonymous.get(refunds));
    assertThat(api.get(refunds).json().get("data"))
        .extracting(r -> r.get("amount").asLong())
        .containsExactly(100L, 500L, 100L);

    webhookEndpoints(anonymous, missing, tooLarge);

    assertThat(exercised).isEqualTo(documentedResponses());
  }

  /** Every documented answer of the webhook endpoint routes. */
  private void webhookEndpoints(Api anonymous, String missing, String tooLarge) {
    String hooks = "/v1/webhook_endpoints";
    String create = "POST " + hooks;
    String body = "{\"url\":\"https://hooks.example.com/payflow\",\"enabled_events\":[\"*\"]}";
    Api.Response created = api.post(hooks, body);
    record(create, created);
    String hook = created.json().get("id").asString();
    replayed(create, 201, hooks, body);
    String ftp = "{\"url\":\"ftp://hooks.example.com\",\"enabled_events\":[\"*\"]}";
    record(create, api.post(hooks, ftp));
    replayed(create, 400, hooks, ftp);
    record(create, api.post(hooks, body, "Idempotency-Key", "has space"));
    record(create, anonymous.post(hooks, body));
    busyKey("busy-hook", hooks, body);
    record(create, api.post(hooks, body, "Idempotency-Key", "busy-hook"));
    record(create, api.post(hooks, tooLarge));
    record(create, api.post(hooks, "<hook/>", "Content-Type", "application/xml"));
    api.post(hooks, body, "Idempotency-Key", "reused-hook");
    record(create, api.post(hooks, ftp, "Idempotency-Key", "reused-hook"));
    // Fill up to the limit; then one more is refused, and the refusal replays.
    for (int i = api.get(hooks).json().get("data").size(); i < 16; i++) {
      assertThat(api.post(hooks, body).status()).isEqualTo(201);
    }
    record(create, api.post(hooks, body));
    replayed(create, 409, hooks, body);

    record("GET " + hooks, api.get(hooks));
    record("GET " + hooks, anonymous.get(hooks));

    String one = hooks + "/{id}";
    String path = hooks + "/" + hook;
    record("GET " + one, api.get(path));
    record("GET " + one, api.get(hooks + "/" + missing));
    record("GET " + one, anonymous.get(path));

    String update = "POST " + one;
    String disable = "{\"state\":\"DISABLED\"}";
    record(update, api.post(path, disable));
    replayed(update, 200, path, "{\"description\":\"replayed\"}");
    record(update, api.post(path, "{}"));
    replayed(update, 400, path, "{}");
    record(update, api.post(path, disable, "Idempotency-Key", "has space"));
    record(update, anonymous.post(path, disable));
    record(update, api.post(hooks + "/" + missing, disable));
    replayed(update, 404, hooks + "/" + missing, disable);
    busyKey("busy-hook-update", path, disable);
    record(update, api.post(path, disable, "Idempotency-Key", "busy-hook-update"));
    record(update, api.post(path, tooLarge));
    record(update, api.post(path, "<hook/>", "Content-Type", "application/xml"));
    api.post(path, disable, "Idempotency-Key", "reused-hook-update");
    record(
        update, api.post(path, "{\"state\":\"ENABLED\"}", "Idempotency-Key", "reused-hook-update"));

    record("DELETE " + one, anonymous.send("DELETE", path, null));
    record("DELETE " + one, api.send("DELETE", path, null));
    record("DELETE " + one, api.send("DELETE", path, null));
  }

  @Test
  void errorsOutsideAnyOperationUseTheDocumentedCrossCuttingCodes() {
    String order = createdOrderId();
    crossCutting(api.get("/v1/no-such-thing"), "not_found");
    crossCutting(api.send("DELETE", "/v1/orders/" + order, null), "method_not_allowed");
    crossCutting(api.send("PUT", "/v1/orders", Api.orderBody()), "method_not_allowed");
    crossCutting(api.get("/v1/orders/" + order, "Accept", "text/html"), "not_acceptable");
    crossCutting(
        api.get("/v1/orders/" + order, "X-Request-Id", "caller-chosen.id_1"),
        null,
        "caller-chosen.id_1");
  }

  @Test
  @SuppressWarnings("unchecked")
  void theSpecListsExactlyTheEventTypesAnEndpointCanSubscribeTo() {
    List<String> documented =
        (List<String>)
            ((Map<String, Object>) validator.resolve("#/components/schemas/WebhookEventType"))
                .get("enum");
    List<String> expected = new java.util.ArrayList<>();
    expected.add(io.github.laiwf94.payflow.webhook.WebhookEvents.ALL);
    expected.addAll(io.github.laiwf94.payflow.webhook.WebhookEvents.TYPES);
    assertThat(documented).isEqualTo(expected);
  }

  @Test
  @SuppressWarnings("unchecked")
  void theSpecDocumentsExactlyTheErrorCodesTheApplicationCanAnswerWith() {
    Map<String, Integer> fromCode = new TreeMap<>();
    for (ApiError e : ApiError.values()) {
      fromCode.put(e.code(), e.status());
      assertThat(ApiError.ofCode(e.code())).isSameAs(e);
    }
    assertThatThrownBy(() -> ApiError.ofCode("nope")).isInstanceOf(IllegalArgumentException.class);

    // The Error schema's enum is the whole list.
    Map<String, Object> code =
        (Map<String, Object>)
            ((Map<String, Object>)
                    ((Map<String, Object>)
                            ((Map<String, Object>) validator.resolve("#/components/schemas/Error"))
                                .get("properties"))
                        .get("error"))
                .get("properties");
    assertThat(new TreeSet<>((List<String>) ((Map<String, Object>) code.get("code")).get("enum")))
        .isEqualTo(fromCode.keySet());

    // Each code an operation documents is documented under the status the application uses.
    Set<String> documented = new TreeSet<>();
    documentedResponses().stream()
        .map(r -> r.split(" "))
        .filter(r -> r.length == 4)
        .forEach(
            r -> {
              assertThat(fromCode)
                  .as(String.join(" ", r))
                  .containsEntry(r[3], Integer.valueOf(r[2]));
              documented.add(r[3]);
            });
    // The rest can happen on any request and are listed once, at the top.
    List<String> crossCutting = (List<String>) spec.get("x-error-codes");
    documented.addAll(crossCutting);
    assertThat(documented).isEqualTo(fromCode.keySet());
    String description = (String) ((Map<String, Object>) spec.get("info")).get("description");
    for (String c : crossCutting) {
      assertThat(description).contains("| " + fromCode.get(c) + " | `" + c + "` |");
    }
  }

  private String createdOrderId() {
    return api.post("/v1/orders", Api.orderBody()).json().get("id").asString();
  }

  /** Leaves {@code key} claimed by a request that is still running. */
  private void busyKey(String key, String path, String body) {
    jdbc.sql(
            """
            INSERT INTO idempotency_keys (merchant_id, idem_key, request_method, request_path,
                request_hash, lock_token, locked_until, created_at)
            VALUES ('acme', :key, 'POST', :path, :hash, gen_random_uuid(),
                now() + interval '1 minute', now())
            """)
        .param("key", key)
        .param("path", path)
        .param(
            "hash",
            idempotency
                .getFilter()
                .hash(
                    "POST",
                    path,
                    body == null
                        ? new byte[0]
                        : body.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
        .update();
  }

  /**
   * Sends the same POST twice with one key and records the second answer, which must be the first
   * one replayed.
   */
  private void replayed(String operation, int status, String path, String body, String... headers) {
    String key = java.util.UUID.randomUUID().toString();
    String[] withKey = java.util.Arrays.copyOf(headers, headers.length + 2);
    withKey[headers.length] = "Idempotency-Key";
    withKey[headers.length + 1] = key;
    Api.Response first = api.post(path, body, withKey);
    Api.Response again = api.post(path, body, withKey);
    assertThat(first.status()).as(operation).isEqualTo(status);
    assertThat(first.header("Idempotent-Replayed")).isNull();
    assertThat(again.header("Idempotent-Replayed")).as(operation + " replay").isEqualTo("true");
    assertThat(again.status()).isEqualTo(status);
    // The body is the first answer, an error's request_id included; the header is this request's.
    assertThat(again.json()).isEqualTo(first.json());
    assertThat(again.header("X-Request-Id")).isNotEqualTo(first.header("X-Request-Id"));
    record(operation, again);
  }

  private void crossCutting(Api.Response r, String expectedCode) {
    crossCutting(r, expectedCode, null);
  }

  /** An answer that belongs to no documented operation: the error body and the request id. */
  @SuppressWarnings("unchecked")
  private void crossCutting(Api.Response r, String expectedCode, String requestId) {
    String id = r.header("X-Request-Id");
    assertThat(id).matches("[A-Za-z0-9._-]{1,64}");
    if (requestId != null) {
      assertThat(id).isEqualTo(requestId);
    }
    if (expectedCode == null) {
      return;
    }
    assertThat(r.errorCode()).isEqualTo(expectedCode);
    assertThat(r.status()).isEqualTo(ApiError.ofCode(expectedCode).status());
    assertThat((List<String>) spec.get("x-error-codes")).contains(expectedCode);
    assertThat(validator.validate(r.json(), Map.of("$ref", "#/components/schemas/Error")))
        .isEmpty();
    assertThat(r.json().path("error").path("request_id").asString()).isEqualTo(id);
  }

  /** Headers the API sets on purpose; each one on a response must be documented there. */
  private static final List<String> API_HEADERS =
      List.of("X-Request-Id", "Idempotent-Replayed", "Location", "WWW-Authenticate");

  /**
   * Validates the response against the spec (status, body, headers and, for an error, its code) and
   * notes which documented response it was.
   */
  @SuppressWarnings("unchecked")
  private void record(String operation, Api.Response r) {
    String[] parts = operation.split(" ", 2);
    Map<String, Object> op =
        (Map<String, Object>)
            ((Map<String, Object>) ((Map<String, Object>) spec.get("paths")).get(parts[1]))
                .get(parts[0].toLowerCase(java.util.Locale.ROOT));
    Map<String, Object> responses = (Map<String, Object>) op.get("responses");
    String status = String.valueOf(r.status());
    String what = operation + " " + status;
    assertThat(responses).as(what + " is documented: " + r.body()).containsKey(status);
    Map<String, Object> response = resolved(responses.get(status));
    Object schema =
        ((Map<String, Object>)
                ((Map<String, Object>) response.get("content")).get("application/json"))
            .get("schema");
    assertThat(r.json()).as(what + " has a JSON body").isNotNull();
    assertThat(validator.validate(r.json(), schema)).as(what).isEmpty();

    Map<String, Object> headers = (Map<String, Object>) response.getOrDefault("headers", Map.of());
    var json = new tools.jackson.databind.ObjectMapper();
    for (String name : API_HEADERS) {
      String value = r.header(name);
      if (value != null) {
        assertThat(headers).as(what + " documents " + name).containsKey(name);
      }
    }
    headers.forEach(
        (name, h) -> {
          Map<String, Object> header = resolved(h);
          String value = r.header(name);
          if (Boolean.TRUE.equals(header.get("required"))) {
            assertThat(value).as(what + " sends " + name).isNotNull();
          }
          if (value != null) {
            assertThat(validator.validate(json.valueToTree(value), header.get("schema")))
                .as(what + " " + name)
                .isEmpty();
          }
        });

    if (r.status() >= 400) {
      String code = r.errorCode();
      assertThat((List<String>) response.get("x-error-codes"))
          .as(what + " lists the code it answered with")
          .contains(code);
      if (r.header("Idempotent-Replayed") == null) {
        assertThat(r.json().path("error").path("request_id").asString())
            .isEqualTo(r.header("X-Request-Id"));
      }
      exercised.add(what + " " + code);
    } else {
      exercised.add(what);
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> resolved(Object node) {
    Map<String, Object> m = (Map<String, Object>) node;
    return m.containsKey("$ref")
        ? (Map<String, Object>) validator.resolve((String) m.get("$ref"))
        : m;
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

  /**
   * Every documented response: {@code "GET /v1/orders 200"} for a success, and one {@code "POST
   * /v1/orders 409 order_reference_in_use"} per code an error response lists.
   */
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
                                .forEach(
                                    (status, response) -> {
                                      String what =
                                          m.toUpperCase(java.util.Locale.ROOT)
                                              + " "
                                              + path
                                              + " "
                                              + status;
                                      List<String> codes =
                                          (List<String>) resolved(response).get("x-error-codes");
                                      if (Integer.parseInt(status) >= 400) {
                                        assertThat(codes)
                                            .as(what + " lists its codes")
                                            .isNotEmpty();
                                        codes.forEach(c -> out.add(what + " " + c));
                                      } else {
                                        assertThat(codes).as(what).isNull();
                                        out.add(what);
                                      }
                                    })));
    return out;
  }

  @Test
  void theValidatorCatchesWhatItShould() {
    Object order = Map.of("$ref", "#/components/schemas/Error");
    var json = new tools.jackson.databind.ObjectMapper();
    assertThat(
            validator.validate(
                json.valueToTree(Map.of("error", Map.of("code", "internal", "message", "y"))),
                order))
        .containsExactly("$.error: missing required request_id");
    assertThat(
            validator.validate(
                json.valueToTree(
                    Map.of("error", Map.of("code", "teapot", "message", "y", "request_id", "r"))),
                order))
        .singleElement()
        .asString()
        .startsWith("$.error.code: teapot is not one of [invalid_argument,");
    assertThat(validator.validate(json.valueToTree(List.of()), order))
        .singleElement()
        .asString()
        .contains("expected object");
    assertThat(
            validator.validate(
                json.valueToTree(
                    Map.of(
                        "error",
                        Map.of("code", "internal", "message", "y", "request_id", "r", "z", 1))),
                order))
        .containsExactly("$.error: unexpected property z");
  }
}
