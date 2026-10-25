package io.github.laiwf94.payflow.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.laiwf94.payflow.support.TestKeys;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiKeyAuthTest {

  @LocalServerPort int port;
  private final HttpClient http = HttpClient.newHttpClient();

  private HttpResponse<String> get(String path, String... headers) throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    for (int i = 0; i < headers.length; i += 2) {
      b.header(headers[i], headers[i + 1]);
    }
    return http.send(b.GET().build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void missingKeyIs401WithTheStandardErrorBody() throws Exception {
    HttpResponse<String> r = get("/v1/orders/x", "X-Request-Id", "req-123");
    assertThat(r.statusCode()).isEqualTo(401);
    assertThat(r.headers().firstValue("WWW-Authenticate")).contains("Bearer");
    assertThat(r.headers().firstValue("X-Request-Id")).contains("req-123");
    assertThat(r.body())
        .isEqualTo(
            "{\"error\":{\"code\":\"unauthenticated\",\"message\":\"missing or invalid API key\","
                + "\"request_id\":\"req-123\"}}");
  }

  @Test
  void wrongOrMalformedKeysAreRejected() throws Exception {
    assertThat(get("/v1/x", "Authorization", "Bearer pf_wrong").statusCode()).isEqualTo(401);
    assertThat(get("/v1/x", "Authorization", "Basic " + TestKeys.ACME).statusCode()).isEqualTo(401);
    assertThat(get("/v1/x", "Authorization", "Bearer").statusCode()).isEqualTo(401);
    assertThat(get("/v1/x", "Authorization", "bearer " + TestKeys.ACME).statusCode())
        .isEqualTo(401);
  }

  @Test
  void aValidKeyGetsPastAuthentication() throws Exception {
    assertThat(get("/v1/nothing-here", "Authorization", "Bearer " + TestKeys.ACME).statusCode())
        .isEqualTo(404);
  }

  @Test
  void unsafeRequestIdsAreReplaced() throws Exception {
    HttpResponse<String> r = get("/v1/x", "X-Request-Id", "bad id with spaces");
    String id = r.headers().firstValue("X-Request-Id").orElseThrow();
    assertThat(id).matches("[0-9a-f-]{36}");
    assertThat(r.body()).contains(id);
    assertThat(get("/v1/x", "X-Request-Id", "x".repeat(65)).headers().firstValue("X-Request-Id"))
        .get()
        .asString()
        .hasSize(36);
  }
}
