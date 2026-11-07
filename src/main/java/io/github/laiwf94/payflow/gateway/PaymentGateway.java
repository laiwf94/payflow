package io.github.laiwf94.payflow.gateway;

import io.github.laiwf94.payflow.money.Money;
import java.util.List;

/**
 * A card payment provider. Every mutating call carries an idempotency key, and the caller uses the
 * same key for every attempt of the same step.
 */
public interface PaymentGateway {

  /** Holds {@code amount}. A decline is {@link CallResult.Rejected} with the decline code. */
  CallResult<PaymentInfo> authorize(
      String idempotencyKey, String reference, Money amount, String paymentMethod);

  CallResult<PaymentInfo> capture(String idempotencyKey, String paymentId, long amount);

  CallResult<PaymentInfo> voidAuthorization(String idempotencyKey, String paymentId);

  /**
   * Returns {@code amount} of a captured payment. {@code reference} is the caller's id for the
   * refund, which {@link #findRefunds} reports back.
   */
  CallResult<RefundInfo> refund(
      String idempotencyKey, String paymentId, long amount, String reference);

  /** Every refund the provider holds for a payment. */
  CallResult<List<RefundInfo>> findRefunds(String paymentId);

  /** Every payment the provider holds for {@code reference}, including declined ones. */
  CallResult<List<PaymentInfo>> findByReference(String reference);
}
