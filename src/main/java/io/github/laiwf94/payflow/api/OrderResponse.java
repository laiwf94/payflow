package io.github.laiwf94.payflow.api;

import io.github.laiwf94.payflow.order.LineItem;
import io.github.laiwf94.payflow.order.Order;
import io.github.laiwf94.payflow.order.ShippingAddress;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** An order as the API returns it. Saga bookkeeping (leases, attempts) stays internal. */
public record OrderResponse(
    UUID id,
    String state,
    long amount,
    String currency,
    List<LineItem> items,
    ShippingAddress shippingAddress,
    String paymentMethod,
    boolean cancelRequested,
    Payment payment,
    Shipment shipment,
    Failure failure,
    Instant createdAt,
    Instant updatedAt) {

  public record Payment(String id, long capturedAmount, long refundedAmount) {}

  public record Shipment(String id, String trackingNumber) {}

  public record Failure(String code, String message) {}

  public static OrderResponse of(Order o) {
    return new OrderResponse(
        o.id(),
        o.state().name(),
        o.amount().minorUnits(),
        o.amount().currencyCode(),
        o.items(),
        o.shippingAddress(),
        o.paymentMethod(),
        o.cancelRequested(),
        o.paymentId() == null
            ? null
            : new Payment(o.paymentId(), o.capturedMinor(), o.refundedMinor()),
        o.shipmentId() == null ? null : new Shipment(o.shipmentId(), o.trackingNumber()),
        o.failureCode() == null ? null : new Failure(o.failureCode(), o.failureMessage()),
        o.createdAt(),
        o.updatedAt());
  }
}
