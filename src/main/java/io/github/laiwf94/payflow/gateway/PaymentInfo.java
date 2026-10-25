package io.github.laiwf94.payflow.gateway;

/** A payment as the provider reports it. Amounts are minor units. */
public record PaymentInfo(
    String id,
    String reference,
    long amount,
    String currency,
    PaymentStatus status,
    long captured,
    long refunded,
    String declineCode) {

  /** Money still held: captured and not yet refunded. */
  public long netCaptured() {
    return captured - refunded;
  }
}
