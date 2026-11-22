package io.github.laiwf94.payflow.api;

import io.github.laiwf94.payflow.order.LineItem;
import io.github.laiwf94.payflow.order.Order;
import io.github.laiwf94.payflow.order.ShippingAddress;
import io.github.laiwf94.payflow.payment.Payment;
import io.github.laiwf94.payflow.refund.RefundSummary;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An order as the API returns it. Saga bookkeeping (leases, attempts) stays internal. {@code
 * cancelReason} says why a cancel was requested ({@code requested}, {@code reservation_expired} or
 * {@code payment_expired}). {@code captureMode} is {@code automatic} or {@code manual}, and {@code
 * captureRequested} says a manual order's capture was asked for. {@code refunds} holds the order's
 * newest refunds and what its pending ones are returning. {@code paymentMethod} and {@code payment}
 * describe the order's tender, read from its {@link Payment} row.
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
    PaymentSummary payment,
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
  public record PaymentSummary(
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

  /**
   * An order that has no refunds: a new one, or one not yet completed.
   *
   * @param payments the order's tenders by position, as {@code PaymentRepository} reads them
   */
  public static OrderResponse of(Order o, List<Payment> payments) {
    return of(o, payments, RefundSummary.NONE);
  }

  /**
   * The order with its tenders and refunds.
   *
   * @throws IllegalStateException when {@code payments} is empty or belongs to another order
   */
  public static OrderResponse of(Order o, List<Payment> payments, RefundSummary refunds) {
    if (payments.isEmpty()) {
      throw new IllegalStateException("order " + o.id() + " has no payment row");
    }
    Payment tender = payments.getFirst();
    if (!tender.orderId().equals(o.id())) {
      throw new IllegalStateException(
          "payment " + tender.id() + " belongs to order " + tender.orderId() + ", not " + o.id());
    }
    return new OrderResponse(
        o.id(),
        o.merchantReference(),
        o.state().name(),
        o.amount().minorUnits(),
        o.amount().currencyCode(),
        o.items(),
        o.shippingAddress(),
        tender.method(),
        o.captureMode().code(),
        o.captureRequested(),
        o.cancelRequested(),
        o.cancelReason() == null ? null : o.cancelReason().code(),
        tender.providerPaymentId() == null
            ? null
            : new PaymentSummary(
                tender.providerPaymentId(),
                tender.capturedMinor(),
                tender.refundedMinor(),
                tender.authorizationExpiresAt(),
                tender.reauthorizedAt()),
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
