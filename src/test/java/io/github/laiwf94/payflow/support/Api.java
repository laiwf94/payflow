package io.github.laiwf94.payflow.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** A small HTTP client for API tests, over a real socket. */
public final class Api {

  /** A response with its body parsed when it is JSON. */
  public record Response(int status, String body, JsonNode json, HttpResponse<String> raw) {
    public String header(String name) {
      return raw.headers().firstValue(name).orElse(null);
    }

    public String errorCode() {
      return json == null ? null : json.path("error").path("code").asText(null);
    }
  }

  private static final ObjectMapper JSON = new ObjectMapper();

  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private final int port;
  private final String key;

  public Api(int port, String key) {
    this.port = port;
    this.key = key;
  }

  public Api as(String otherKey) {
    return new Api(port, otherKey);
  }

  public Response get(String path, String... headers) {
    return send("GET", path, null, headers);
  }

  public Response post(String path, String body, String... headers) {
    return send("POST", path, body, headers);
  }

  public Response send(String method, String path, String body, String... headers) {
    HttpRequest.Builder b =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .timeout(Duration.ofSeconds(30))
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body));
    if (key != null) {
      b.header("Authorization", "Bearer " + key);
    }
    if (body != null) {
      b.header("Content-Type", "application/json");
    }
    for (int i = 0; i < headers.length; i += 2) {
      b.setHeader(headers[i], headers[i + 1]);
    }
    try {
      HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
      JsonNode json = null;
      if (r.body() != null && r.body().startsWith("{")) {
        json = JSON.readTree(r.body());
      }
      return new Response(r.statusCode(), r.body(), json, r);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  /** A valid order body for 2 x 10.00 + 1 x 5.00 EUR. */
  public static String orderBody() {
    return orderBody("pm_card_ok", "12345");
  }

  public static String orderBody(String paymentMethod, String postalCode) {
    return """
        {"items":[{"sku":"sku-1","quantity":2,"unit_amount":1000},
                  {"sku":"sku-2","quantity":1,"unit_amount":500}],
         "currency":"EUR",
         "shipping_address":{"name":"Alice Example","line1":"1 Main Street","city":"Springfield",
                             "postal_code":"%s","country":"NL"},
         "payment_method":"%s"}
        """
        .formatted(postalCode, paymentMethod);
  }
}
