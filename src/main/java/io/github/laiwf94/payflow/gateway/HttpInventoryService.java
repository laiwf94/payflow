package io.github.laiwf94.payflow.gateway;

import io.github.laiwf94.payflow.order.LineItem;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpMethod;

/** {@link InventoryService} over the inventory system's HTTP API. */
final class HttpInventoryService implements InventoryService {

  private final HttpDependency http;

  HttpInventoryService(HttpDependency http) {
    this.http = http;
  }

  @Override
  public CallResult<String> reserve(String reservationId, List<LineItem> items) {
    List<Map<String, Object>> lines =
        items.stream()
            .map(i -> Map.<String, Object>of("sku", i.sku(), "quantity", i.quantity()))
            .toList();
    return http.call(
        HttpMethod.POST,
        Map.of("reservation_id", reservationId, "lines", lines),
        Map.of(),
        u -> u,
        (status, body) ->
            status >= 300
                ? HttpDependency.rejected(body, "reservation_failed")
                : new CallResult.Success<>(body.path("id").asString(reservationId)));
  }

  @Override
  public CallResult<Boolean> release(String reservationId) {
    return http.call(
        HttpMethod.DELETE,
        null,
        Map.of(),
        u -> u.pathSegment(reservationId),
        (status, body) ->
            status == 404 || status < 300
                ? new CallResult.Success<>(Boolean.TRUE)
                : HttpDependency.rejected(body, "release_failed"));
  }
}
