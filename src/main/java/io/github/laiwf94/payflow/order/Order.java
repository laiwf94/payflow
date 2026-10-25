package io.github.laiwf94.payflow.order;

import io.github.laiwf94.payflow.money.Money;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** An order as stored, including the saga bookkeeping. */
public record Order(
    UUID id,
    String merchantId,
    OrderState state,
    Money amount,
    List<LineItem> items,
    ShippingAddress shippingAddress,
    String paymentMethod,
    boolean cancelRequested,
    String reservationId,
    String paymentId,
    String shipmentId,
    String trackingNumber,
    long capturedMinor,
    long refundedMinor,
    String failureCode,
    String failureMessage,
    int attempt,
    Instant stepStartedAt,
    Instant nextAttemptAt,
    String correlationId,
    long version,
    Instant createdAt,
    Instant updatedAt) {

  public Order {
    items = List.copyOf(items);
  }

  /** A new order in {@code PENDING}, due immediately. */
  public static Order create(
      UUID id,
      String merchantId,
      Money amount,
      List<LineItem> items,
      ShippingAddress shippingAddress,
      String paymentMethod,
      String correlationId,
      Instant now) {
    return new Order(
        id,
        merchantId,
        OrderState.PENDING,
        amount,
        items,
        shippingAddress,
        paymentMethod,
        false,
        null,
        null,
        null,
        null,
        0,
        0,
        null,
        null,
        0,
        now,
        now,
        correlationId,
        0,
        now,
        now);
  }
}
