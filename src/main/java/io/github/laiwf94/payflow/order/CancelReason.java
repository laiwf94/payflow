package io.github.laiwf94.payflow.order;

import java.util.Locale;
import java.util.Set;

/**
 * Why an order is being cancelled. Stored in {@code orders.cancel_reason} alongside {@code
 * cancel_requested}, and never cleared, so a {@code CANCELLED} order says why.
 */
public enum CancelReason {
  /** The merchant asked, with {@code POST /v1/orders/{id}/cancel}. */
  REQUESTED,

  /** The order held stock for the whole reservation window without its payment being captured. */
  RESERVATION_EXPIRED,

  /** The order's payment was not captured within the payment window after it was created. */
  PAYMENT_EXPIRED;

  private static final Set<OrderState> BEFORE_CAPTURE =
      Set.of(OrderState.PENDING, OrderState.INVENTORY_RESERVED, OrderState.PAYMENT_AUTHORIZED);

  private static final Set<OrderState> HOLDING_STOCK =
      Set.of(OrderState.INVENTORY_RESERVED, OrderState.PAYMENT_AUTHORIZED);

  /**
   * The states an order can be cancelled from for this reason, all before capture. A reservation
   * can only run out once there is one.
   */
  public Set<OrderState> appliesTo() {
    return this == RESERVATION_EXPIRED ? HOLDING_STOCK : BEFORE_CAPTURE;
  }

  /** The value stored and returned by the API, e.g. {@code reservation_expired}. */
  public String code() {
    return name().toLowerCase(Locale.ROOT);
  }

  /** The reason stored as {@code code}, or null for none. */
  public static CancelReason fromCode(String code) {
    return code == null ? null : valueOf(code.toUpperCase(Locale.ROOT));
  }
}
