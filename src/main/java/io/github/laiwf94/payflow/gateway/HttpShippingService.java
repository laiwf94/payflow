package io.github.laiwf94.payflow.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.laiwf94.payflow.order.ShippingAddress;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpMethod;

/** {@link ShippingService} over the carrier's HTTP API. */
final class HttpShippingService implements ShippingService {

  private final HttpDependency http;

  HttpShippingService(HttpDependency http) {
    this.http = http;
  }

  @Override
  public CallResult<ShipmentInfo> create(String reference, ShippingAddress address) {
    return http.call(
        HttpMethod.POST,
        Map.of(
            "reference", reference,
            "postal_code", address.postalCode(),
            "country", address.country()),
        Map.of(),
        u -> u,
        this::shipment);
  }

  @Override
  public CallResult<ShipmentInfo> cancel(String shipmentId) {
    return http.call(
        HttpMethod.POST, null, Map.of(), u -> u.pathSegment(shipmentId, "cancel"), this::shipment);
  }

  @Override
  public CallResult<Optional<ShipmentInfo>> findByReference(String reference) {
    return http.call(
        HttpMethod.GET,
        null,
        Map.of(),
        u -> u.queryParam("reference", reference),
        (status, body) -> {
          if (status >= 300) {
            return HttpDependency.rejected(body, "lookup_failed");
          }
          JsonNode data = body.path("data");
          return new CallResult.Success<>(
              data.isArray() && !data.isEmpty()
                  ? Optional.of(http.read(data.get(0), ShipmentInfo.class))
                  : Optional.empty());
        });
  }

  private CallResult<ShipmentInfo> shipment(int status, JsonNode body) {
    if (status >= 300) {
      return HttpDependency.rejected(body, "shipping_error");
    }
    return new CallResult.Success<>(http.read(body, ShipmentInfo.class));
  }
}
