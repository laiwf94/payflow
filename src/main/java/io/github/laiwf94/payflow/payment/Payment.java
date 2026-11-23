package io.github.laiwf94.payflow.payment;

import io.github.laiwf94.payflow.money.Money;
import java.time.Instant;
import java.util.UUID;

/**
 * One tender of an order: the part of its amount paid with {@code method} through {@code provider},
 * and where that money is. An order's tenders are numbered from 1 by {@code position}, the order
 * they are authorized in, and their amounts add up to the order's.
 *
 * <p>The payment steps own these rows: each records what the provider did with this tender, in the
 * same transaction as the order's own step write, so the order's money columns are always the sum
 * of its tenders'. The provider knows the tender by {@link #providerReference()}.
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
    if (amount == null || state == null) {
      throw new IllegalArgumentException("a tender needs an amount and a state");
    }
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

  /**
   * A new tender, {@code pending}, for {@code amount} of order {@code orderId} paid with {@code
   * method}. The keys of the first tender are the ones orders have always sent ({@code
   * authorize-<order>} and so on), so an order whose step resumes across an upgrade sends the same
   * key again; later tenders add their position.
   */
  public static Payment create(
      UUID id, UUID orderId, int position, String method, Money amount, Instant now) {
    String suffix = position == 1 ? "" : "-" + position;
    return new Payment(
        id,
        orderId,
        position,
        method,
        PRIMARY_PROVIDER,
        amount,
        PaymentState.PENDING,
        null,
        "authorize-" + orderId + suffix,
        "capture-" + orderId + suffix,
        "reauthorize-" + orderId + suffix,
        0,
        0,
        null,
        null,
        now,
        now);
  }

  /**
   * The reference the tender's authorization is made under, which the provider's lookup by
   * reference finds it by: the order id for the first tender, as before split tender, and the order
   * id and position for the others, so each tender's lookup finds only its own payments.
   */
  public String providerReference() {
    return position == 1 ? orderId.toString() : orderId + "-" + position;
  }

  /** What the tender holds of the customer's money after refunds, in minor units. */
  public long netCapturedMinor() {
    return capturedMinor - refundedMinor;
  }

  /** True once the provider has held or taken money on this tender and it isn't all back. */
  public boolean holdsMoney() {
    return state == PaymentState.AUTHORIZED || netCapturedMinor() > 0;
  }

  /**
   * The tender authorized as {@code providerPaymentId}, with its hold lapsing at {@code expires}.
   */
  public Payment authorized(String providerPaymentId, Instant expires) {
    return with(PaymentState.AUTHORIZED, providerPaymentId, 0, 0, expires, reauthorizedAt);
  }

  /** The tender's authorization extended at {@code at}, now lapsing at {@code expires}. */
  public Payment reauthorized(Instant expires, Instant at) {
    return with(state, providerPaymentId, capturedMinor, refundedMinor, expires, at);
  }

  /**
   * The tender with the money the provider reports for it, in the state that money implies, or
   * {@code otherwise} when it holds none: a tender that never had anything captured ends voided,
   * expired, declined or canceled, as the provider says. {@code providerPaymentId} null keeps the
   * recorded id.
   */
  public Payment settled(
      String providerPaymentId, long captured, long refunded, PaymentState otherwise) {
    PaymentState s;
    if (captured == 0) {
      s = otherwise;
    } else if (refunded == 0) {
      s = PaymentState.CAPTURED;
    } else {
      s = refunded == captured ? PaymentState.REFUNDED : PaymentState.PARTIALLY_REFUNDED;
    }
    return with(
        s,
        providerPaymentId != null ? providerPaymentId : this.providerPaymentId,
        captured,
        refunded,
        authorizationExpiresAt,
        reauthorizedAt);
  }

  private Payment with(
      PaymentState state,
      String providerPaymentId,
      long capturedMinor,
      long refundedMinor,
      Instant authorizationExpiresAt,
      Instant reauthorizedAt) {
    return new Payment(
        id,
        orderId,
        position,
        method,
        provider,
        amount,
        state,
        providerPaymentId,
        authorizeKey,
        captureKey,
        reauthorizeKey,
        capturedMinor,
        refundedMinor,
        authorizationExpiresAt,
        reauthorizedAt,
        createdAt,
        updatedAt);
  }
}
