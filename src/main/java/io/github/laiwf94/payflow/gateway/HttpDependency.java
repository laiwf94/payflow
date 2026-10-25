package io.github.laiwf94.payflow.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.time.Duration;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * One remote dependency over HTTP, with the outcome rules in one place.
 *
 * <p>The rules lean towards "unknown": only a failure that provably happened before the request
 * reached the other side (no connection, or an explicit 503/429) counts as transient. Anything
 * after the request was sent, including a 500, may have been applied.
 */
final class HttpDependency {

  private static final Logger log = LoggerFactory.getLogger(HttpDependency.class);

  /** Maps a 2xx or 4xx answer to a result. */
  interface Answer<T> {
    CallResult<T> map(int status, JsonNode body);
  }

  private final String name;
  private final Supplier<String> baseUrl;
  private final Supplier<String> apiKey;
  private final ObjectMapper json;
  private final RestClient client;

  HttpDependency(
      String name,
      Supplier<String> baseUrl,
      Supplier<String> apiKey,
      Duration connectTimeout,
      Duration readTimeout,
      RestClient.Builder builder,
      ObjectMapper json) {
    this.name = name;
    this.baseUrl = baseUrl;
    this.apiKey = apiKey;
    this.json = json;
    HttpClient http =
        HttpClient.newBuilder()
            .connectTimeout(connectTimeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
    factory.setReadTimeout(readTimeout);
    this.client = builder.clone().requestFactory(factory).build();
  }

  /**
   * Calls {@code base + path}. Path segments are encoded one by one, so an id that came back in a
   * response body can't add path components or a query.
   */
  <T> CallResult<T> call(
      HttpMethod method,
      Object body,
      Map<String, String> headers,
      Function<UriComponentsBuilder, UriComponentsBuilder> path,
      Answer<T> answer) {
    URI uri =
        path.apply(UriComponentsBuilder.fromUriString(baseUrl.get())).encode().build().toUri();
    try {
      RestClient.RequestBodySpec req =
          client
              .method(method)
              .uri(uri)
              .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey.get())
              .accept(MediaType.APPLICATION_JSON);
      headers.forEach(req::header);
      if (body != null) {
        req.contentType(MediaType.APPLICATION_JSON).body(body);
      }
      return req.exchange(
          (request, response) -> {
            int status = response.getStatusCode().value();
            byte[] bytes = response.getBody().readAllBytes();
            return classify(status, bytes, answer);
          },
          true);
    } catch (ResourceAccessException e) {
      return classifyIo(e);
    }
  }

  <T> CallResult<T> classify(int status, byte[] bytes, Answer<T> answer) {
    if (status == 429 || status == 503) {
      return new CallResult.Transient<>(name + " returned " + status);
    }
    if (status == 401 || status == 403) {
      // Our credentials are wrong: retrying won't fix it, but failing the customer's order for
      // our own misconfiguration would be worse. Retry, and make noise.
      log.error("{} refused our credentials with {}", name, status);
      return new CallResult.Transient<>(name + " refused credentials");
    }
    if (status == 408 || status >= 500) {
      return new CallResult.Unknown<>(name + " returned " + status);
    }
    if (status < 200 || (status >= 300 && status < 400)) {
      return new CallResult.Unknown<>(name + " returned unexpected " + status);
    }
    JsonNode node;
    try {
      node = bytes.length == 0 ? json.nullNode() : json.readTree(bytes);
    } catch (IOException e) {
      return status < 300
          ? new CallResult.Unknown<>(name + " returned an unreadable body")
          : new CallResult.Rejected<>("unreadable_error", name + " returned " + status);
    }
    return answer.map(status, node);
  }

  private <T> CallResult<T> classifyIo(ResourceAccessException e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof HttpConnectTimeoutException
          || t instanceof ConnectException
          || t instanceof UnresolvedAddressException) {
        return new CallResult.Transient<>(name + " unreachable");
      }
      if (t instanceof HttpTimeoutException) {
        return new CallResult.Unknown<>(name + " timed out");
      }
    }
    return new CallResult.Unknown<>(name + " connection failed after sending");
  }

  <T> T read(JsonNode node, Class<T> type) {
    try {
      return json.treeToValue(node, type);
    } catch (IOException e) {
      throw new IllegalStateException(name + " returned an unexpected body", e);
    }
  }

  /** The {@code error.code} and {@code error.message} of a provider error body. */
  static <T> CallResult<T> rejected(JsonNode body, String fallbackCode) {
    JsonNode err = body.path("error");
    return new CallResult.Rejected<>(
        err.path("code").asText(fallbackCode), err.path("message").asText(""));
  }
}
