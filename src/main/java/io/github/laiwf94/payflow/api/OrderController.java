package io.github.laiwf94.payflow.api;

import io.github.laiwf94.payflow.order.LineItem;
import io.github.laiwf94.payflow.order.Order;
import io.github.laiwf94.payflow.order.OrderService;
import io.github.laiwf94.payflow.order.ShippingAddress;
import io.github.laiwf94.payflow.security.ApiKeyFilter;
import io.github.laiwf94.payflow.timeline.TimelineEntry;
import io.github.laiwf94.payflow.timeline.TimelineProjection;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The merchant API for orders. Every route is scoped to the authenticated merchant. */
@RestController
@RequestMapping(path = "/v1/orders", produces = MediaType.APPLICATION_JSON_VALUE)
class OrderController {

  private final OrderService orders;
  private final TimelineProjection timeline;

  OrderController(OrderService orders, TimelineProjection timeline) {
    this.orders = orders;
    this.timeline = timeline;
  }

  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<OrderResponse> create(
      @Valid @RequestBody CreateOrderRequest req, HttpServletRequest http) {
    CreateOrderRequest.Address a = req.shippingAddress();
    Order o =
        orders.create(
            merchant(http),
            req.items().stream()
                .map(i -> new LineItem(i.sku(), i.quantity(), i.unitAmount()))
                .toList(),
            req.currency(),
            new ShippingAddress(
                a.name(), a.line1(), a.line2(), a.city(), a.postalCode(), a.country()),
            req.paymentMethod(),
            RequestIds.of(http));
    return ResponseEntity.created(URI.create("/v1/orders/" + o.id())).body(OrderResponse.of(o));
  }

  @GetMapping("/{id}")
  OrderResponse get(@PathVariable String id, HttpServletRequest http) {
    return OrderResponse.of(orders.get(merchant(http), parse(id)));
  }

  @PostMapping("/{id}/cancel")
  ResponseEntity<OrderResponse> cancel(@PathVariable String id, HttpServletRequest http) {
    return ResponseEntity.accepted()
        .body(OrderResponse.of(orders.cancel(merchant(http), parse(id))));
  }

  @PostMapping("/{id}/refund")
  ResponseEntity<OrderResponse> refund(@PathVariable String id, HttpServletRequest http) {
    return ResponseEntity.accepted()
        .body(OrderResponse.of(orders.refund(merchant(http), parse(id))));
  }

  /**
   * The order's event history as the consumer has recorded it so far. It trails the order itself by
   * however long the outbox relay and the consumer take.
   */
  @GetMapping("/{id}/events")
  Map<String, List<TimelineEntry>> events(@PathVariable String id, HttpServletRequest http) {
    UUID orderId = parse(id);
    String merchant = merchant(http);
    orders.get(merchant, orderId);
    return Map.of("data", timeline.forOrder(merchant, orderId));
  }

  private static String merchant(HttpServletRequest http) {
    return (String) http.getAttribute(ApiKeyFilter.MERCHANT_ATTRIBUTE);
  }

  /** A malformed id can't name an order, so it is simply not found. */
  private static UUID parse(String id) {
    try {
      if (id.length() == 36) {
        return UUID.fromString(id);
      }
    } catch (IllegalArgumentException e) {
      // fall through
    }
    throw new OrderNotFound();
  }

  static final class OrderNotFound extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }
}
