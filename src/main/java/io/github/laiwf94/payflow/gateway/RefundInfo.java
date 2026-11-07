package io.github.laiwf94.payflow.gateway;

/**
 * A refund as the provider reports it. {@code reference} is the id the caller gave it, which is how
 * a caller recognises its own refund among a payment's refunds. The amount is in minor units.
 */
public record RefundInfo(String id, String paymentId, String reference, long amount) {}
