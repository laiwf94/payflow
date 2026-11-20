package io.github.laiwf94.payflow.order;

import io.github.laiwf94.payflow.money.Money;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An order as stored, including the saga bookkeeping. {@code cancelReason} is set exactly when
 * {@code cancelRequested} is, except on orders cancelled before reasons were recorded. {@code
 * captureRequested} is only ever true for a {@link CaptureMode#MANUAL} order, once the merchant
 * asked for the capture; {@code authorizationExpiresAt} is set when the payment is authorized.
 * {@code reauthorizationRequested} is set, on a manual-capture order only, when its authorization
 * nears expiry, and {@code reauthorizedAt} once the authorization was extended, at most once.
 */
public record Order(
    UUID id,
    String merchantId,
    String merchantReference,
    OrderState state,
    Money amount,
    List<LineItem> items,
    ShippingAddress shippingAddress,
    String paymentMethod,
    CaptureMode captureMode,
    boolean cancelRequested,
    CancelReason cancelReason,
    boolean captureRequested,
    String reservationId,
    String paymentId,
    String shipmentId,
    String trackingNumber,
    long capturedMinor,
    long refundedMinor,
    Instant authorizationExpiresAt,
    boolean reauthorizationRequested,
    Instant reauthorizedAt,
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
    if (captureRequested && captureMode != CaptureMode.MANUAL) {
      throw new IllegalArgumentException("only a manual-capture order can have a capture request");
    }
    if ((reauthorizationRequested && captureMode != CaptureMode.MANUAL)
        || (reauthorizedAt != null && !reauthorizationRequested)) {
      throw new IllegalArgumentException(
          "only a manual-capture order asked to re-authorize can be re-authorized");
    }
  }

  /** A new order in {@code PENDING} without a merchant reference, due immediately. */
  public static Order create(
      UUID id,
      String merchantId,
      Money amount,
      List<LineItem> items,
      ShippingAddress shippingAddress,
      String paymentMethod,
      String correlationId,
      Instant now) {
    return create(
        id, merchantId, null, amount, items, shippingAddress, paymentMethod, correlationId, now);
  }

  /**
   * A new order in {@code PENDING}, due immediately, that captures automatically. {@code
   * merchantReference} is the merchant's own id for it, unique per merchant, or null.
   */
  public static Order create(
      UUID id,
      String merchantId,
      String merchantReference,
      Money amount,
      List<LineItem> items,
      ShippingAddress shippingAddress,
      String paymentMethod,
      String correlationId,
      Instant now) {
    return create(
        id,
        merchantId,
        merchantReference,
        CaptureMode.AUTOMATIC,
        amount,
        items,
        shippingAddress,
        paymentMethod,
        correlationId,
        now);
  }

  /** A new order in {@code PENDING}, due immediately, captured as {@code captureMode} says. */
  public static Order create(
      UUID id,
      String merchantId,
      String merchantReference,
      CaptureMode captureMode,
      Money amount,
      List<LineItem> items,
      ShippingAddress shippingAddress,
      String paymentMethod,
      String correlationId,
      Instant now) {
    return new Order(
        id,
        merchantId,
        merchantReference,
        OrderState.PENDING,
        amount,
        items,
        shippingAddress,
        paymentMethod,
        captureMode,
        false,
        null,
        false,
        null,
        null,
        null,
        null,
        0,
        0,
        null,
        false,
        null,
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
