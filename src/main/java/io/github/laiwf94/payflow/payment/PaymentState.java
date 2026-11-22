package io.github.laiwf94.payflow.payment;

import java.util.Locale;

/** Where one tender's money is. Stored in {@code payments.state} as its {@link #code()}. */
public enum PaymentState {
  /** Not authorized yet. */
  PENDING,
  /** The amount is held on the method and nothing is captured. */
  AUTHORIZED,
  /** The amount is captured and none of it is refunded. */
  CAPTURED,
  /** Some, not all, of the capture is refunded. */
  PARTIALLY_REFUNDED,
  /** The whole capture is refunded. */
  REFUNDED,
  /** The authorization was released without capturing anything. */
  VOIDED,
  /** The authorization lapsed before it was captured. */
  EXPIRED,
  /** The provider refused the authorization. */
  DECLINED,
  /** The order ended before this tender held any money. */
  CANCELED;

  /** The value stored, e.g. {@code partially_refunded}. */
  public String code() {
    return name().toLowerCase(Locale.ROOT);
  }

  /** True once nothing more will happen to the tender's money except a refund. */
  public boolean isSettled() {
    return this != PENDING && this != AUTHORIZED;
  }

  /**
   * The state stored as {@code code}.
   *
   * @throws IllegalArgumentException for anything else
   */
  public static PaymentState fromCode(String code) {
    for (PaymentState s : values()) {
      if (s.code().equals(code)) {
        return s;
      }
    }
    throw new IllegalArgumentException("unknown payment state: " + code);
  }
}
