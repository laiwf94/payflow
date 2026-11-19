package io.github.laiwf94.payflow.order;

import java.util.Locale;

/**
 * Who takes the money once an order's payment is authorized. Stored in {@code orders.capture_mode}
 * and chosen when the order is created.
 */
public enum CaptureMode {
  /** The saga captures as soon as the payment is authorized. */
  AUTOMATIC,

  /**
   * The order waits in {@code PAYMENT_AUTHORIZED} until the merchant asks for the capture with
   * {@code POST /v1/orders/{id}/capture}, typically when the goods ship, or until the authorization
   * expires, when it fails into the reversal.
   */
  MANUAL;

  /** The value stored and returned by the API, e.g. {@code manual}. */
  public String code() {
    return name().toLowerCase(Locale.ROOT);
  }

  /**
   * The mode stored as {@code code}.
   *
   * @throws IllegalArgumentException for anything else
   */
  public static CaptureMode fromCode(String code) {
    for (CaptureMode m : values()) {
      if (m.code().equals(code)) {
        return m;
      }
    }
    throw new IllegalArgumentException("unknown capture mode: " + code);
  }
}
