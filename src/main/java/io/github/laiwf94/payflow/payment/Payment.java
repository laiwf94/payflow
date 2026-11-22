package io.github.laiwf94.payflow.payment;

import io.github.laiwf94.payflow.money.Money;
import java.time.Instant;
import java.util.UUID;

/**
 * One tender of an order: the part of its amount paid with {@code method} through {@code provider},
 * and where that money is. An order's tenders are numbered from 1 by {@code position}, the order
 * they are authorized in. Today every order has one tender for its whole amount.
 *
 * @param providerPaymentId the provider's id for the authorization, once it is known
 * @param authorizeKey the idempotency key every authorization attempt for this tender sends
 * @param captureKey the idempotency key every capture attempt for this tender sends
 * @param reauthorizeKey the idempotency key of the one extension of this tender's authorization
 * @param capturedMinor what the provider captured, in minor units
 * @param refundedMinor what was given back of the capture, in minor units
 * @param authorizationExpiresAt when the hold lapses, once authorized (absent on tenders authorized
 *     before it was recorded)
 * @param reauthorizedAt when the authorization was extended, if it was
 */
public record Payment(
    UUID id,
    UUID orderId,
    int position,
    String method,
    String provider,
    Money amount,
    PaymentState state,
    String providerPaymentId,
    String authorizeKey,
    String captureKey,
    String reauthorizeKey,
    long capturedMinor,
    long refundedMinor,
    Instant authorizationExpiresAt,
    Instant reauthorizedAt,
    Instant createdAt,
    Instant updatedAt) {

  /** The provider every tender goes to until routing chooses between several. */
  public static final String PRIMARY_PROVIDER = "primary";

  public Payment {
    if (position < 1) {
      throw new IllegalArgumentException("position starts at 1: " + position);
    }
    if (capturedMinor < 0
        || capturedMinor > amount.minorUnits()
        || refundedMinor < 0
        || refundedMinor > capturedMinor) {
      throw new IllegalArgumentException(
          "captured %d and refunded %d don't fit an amount of %d"
              .formatted(capturedMinor, refundedMinor, amount.minorUnits()));
    }
  }

  /** What the tender holds of the customer's money after refunds, in minor units. */
  public long netCapturedMinor() {
    return capturedMinor - refundedMinor;
  }
}
