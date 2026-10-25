package io.github.laiwf94.payflow.saga.steps;

import java.util.UUID;

/**
 * Idempotency keys sent to the payment provider. Each is derived from the order (or payment) and
 * the step, so every attempt of one step sends the same key and different steps never collide.
 */
final class Keys {

  private Keys() {}

  static String authorize(UUID order) {
    return "authorize-" + order;
  }

  static String capture(UUID order) {
    return "capture-" + order;
  }

  static String voidPayment(String paymentId) {
    return "void-" + paymentId;
  }

  static String reverse(String paymentId) {
    return "reverse-" + paymentId;
  }

  static String refund(String paymentId) {
    return "refund-" + paymentId;
  }
}
