package io.github.laiwf94.payflow.gateway;

import java.time.Instant;

/**
 * A payment as the provider reports it. Amounts are minor units. {@code expiresAt} is when the
 * provider says an authorization lapses if it isn't captured; null when it didn't say, or for a
 * payment that holds nothing.
 */
public record PaymentInfo(
    String id,
    String reference,
    long amount,
    String currency,
    PaymentStatus status,
    long captured,
    long refunded,
    String declineCode,
    Instant expiresAt) {

  /** Money still held: captured and not yet refunded. */
  public long netCaptured() {
    return captured - refunded;
  }
}
