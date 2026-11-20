package io.github.laiwf94.payflow.api;

import io.github.laiwf94.payflow.order.LineItem;
import io.github.laiwf94.payflow.order.Order;
import io.github.laiwf94.payflow.order.ShippingAddress;
import io.github.laiwf94.payflow.refund.RefundSummary;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An order as the API returns it. Saga bookkeeping (leases, attempts) stays internal. {@code
 * cancelReason} says why a cancel was requested ({@code requested}, {@code reservation_expired} or
 * {@code payment_expired}). {@code captureMode} is {@code automatic} or {@code manual}, and {@code
 * captureRequested} says a manual order's capture was asked for. {@code refunds} holds the order's
 * newest refunds and what its pending ones are returning.
 */
public record OrderResponse(
    UUID id,
    String merchantReference,
    String state,
    long amount,
    String currency,
    List<LineItem> items,
    ShippingAddress shippingAddress,
    String paymentMethod,
    String captureMode,
    boolean captureRequested,
    boolean cancelRequested,
    String cancelReason,
    Payment payment,
    Shipment shipment,
    Failure failure,
    Refunds refunds,
    Instant createdAt,
    Instant updatedAt) {

  /**
   * The payment once authorized. {@code authorizationExpiresAt} is when the hold on the card lapses
   * (absent on orders authorized before it was recorded); {@code reauthorizedAt} is when the
   * authorization was extended, on a manual-capture order that waited long enough for that.
   */
  public record Payment(
      String id,
      long capturedAmount,
      long refundedAmount,
      Instant authorizationExpiresAt,
      Instant reauthorizedAt) {}

  public record Shipment(String id, String trackingNumber) {}

  public record Failure(String code, String message) {}

  /**
   * The newest refunds, newest first; {@code hasMore} when the order has older ones, which {@code
   * GET /v1/orders/{id}/refunds} lists. {@code pendingAmount} is what refunds still {@code PENDING}
   * are returning, on top of the payment's {@code refundedAmount}.
   */
  public record Refunds(List<RefundResponse> data, boolean hasMore, long pendingAmount) {}

  /** An order that has no refunds: a new one, or one not yet completed. */
  public static OrderResponse of(Order o) {
    return of(o, RefundSummary.NONE);
  }

  public static OrderResponse of(Order o, RefundSummary refunds) {
    return new OrderResponse(
        o.id(),
        o.merchantReference(),
        o.state().name(),
        o.amount().minorUnits(),
        o.amount().currencyCode(),
        o.items(),
        o.shippingAddress(),
        o.paymentMethod(),
        o.captureMode().code(),
        o.captureRequested(),
        o.cancelRequested(),
        o.cancelReason() == null ? null : o.cancelReason().code(),
        o.paymentId() == null
            ? null
            : new Payment(
                o.paymentId(),
                o.capturedMinor(),
                o.refundedMinor(),
                o.authorizationExpiresAt(),
                o.reauthorizedAt()),
        o.shipmentId() == null ? null : new Shipment(o.shipmentId(), o.trackingNumber()),
        o.failureCode() == null ? null : new Failure(o.failureCode(), o.failureMessage()),
        new Refunds(
            refunds.recent().stream()
                .map(r -> RefundResponse.of(r, o.amount().currencyCode()))
                .toList(),
            refunds.hasMore(),
            refunds.pendingMinor()),
        o.createdAt(),
        o.updatedAt());
  }
}
