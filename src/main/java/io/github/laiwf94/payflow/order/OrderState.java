package io.github.laiwf94.payflow.order;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Where an order is in its saga. Forward states record what has been done; compensating states
 * record what is being undone. The same table is enforced by a CHECK constraint in the database
 * (see {@code V1__orders.sql}), and {@code OrderSchemaTest} keeps the two in step.
 *
 * <pre>
 * PENDING -> INVENTORY_RESERVED -> PAYMENT_AUTHORIZED -> PAYMENT_CAPTURED -> COMPLETED -> REFUNDING -> REFUNDED
 *    |              |   \                 |                    |
 *    |              |    +--> REVERSING_PAYMENT <--------------+-- CANCELLING_SHIPMENT
 *    v              v              |
 * RELEASING_INVENTORY <------------+
 *    |
 *    +--> FAILED | CANCELLED
 * </pre>
 */
public enum OrderState {
  PENDING,
  INVENTORY_RESERVED,
  PAYMENT_AUTHORIZED,
  PAYMENT_CAPTURED,
  COMPLETED,
  CANCELLING_SHIPMENT,
  REVERSING_PAYMENT,
  RELEASING_INVENTORY,
  FAILED,
  CANCELLED,
  REFUNDING,
  REFUNDED;

  private static final Map<OrderState, Set<OrderState>> NEXT = new EnumMap<>(OrderState.class);

  static {
    allow(PENDING, INVENTORY_RESERVED, RELEASING_INVENTORY);
    allow(INVENTORY_RESERVED, PAYMENT_AUTHORIZED, REVERSING_PAYMENT, RELEASING_INVENTORY);
    allow(PAYMENT_AUTHORIZED, PAYMENT_CAPTURED, REVERSING_PAYMENT);
    allow(PAYMENT_CAPTURED, COMPLETED, CANCELLING_SHIPMENT);
    allow(CANCELLING_SHIPMENT, REVERSING_PAYMENT);
    allow(REVERSING_PAYMENT, RELEASING_INVENTORY);
    allow(RELEASING_INVENTORY, FAILED, CANCELLED);
    allow(COMPLETED, REFUNDING);
    allow(REFUNDING, REFUNDED);
    for (OrderState s : values()) {
      NEXT.putIfAbsent(s, Collections.unmodifiableSet(EnumSet.noneOf(OrderState.class)));
    }
  }

  private static void allow(OrderState from, OrderState first, OrderState... rest) {
    NEXT.put(from, Collections.unmodifiableSet(EnumSet.of(first, rest)));
  }

  public boolean canTransitionTo(OrderState next) {
    return NEXT.get(this).contains(next);
  }

  public Set<OrderState> next() {
    return NEXT.get(this);
  }

  /**
   * True when the saga has nothing left to do. {@code COMPLETED} is terminal for the saga even
   * though a refund can later move it on.
   */
  public boolean isTerminal() {
    return this == COMPLETED || this == FAILED || this == CANCELLED || this == REFUNDED;
  }

  /** True for steps that undo work. They retry until they succeed instead of giving up. */
  public boolean isCompensating() {
    return this == CANCELLING_SHIPMENT
        || this == REVERSING_PAYMENT
        || this == RELEASING_INVENTORY
        || this == REFUNDING;
  }

  /** A cancel request is accepted only before money has been captured. */
  public boolean isCancellable() {
    return this == PENDING || this == INVENTORY_RESERVED || this == PAYMENT_AUTHORIZED;
  }

  /**
   * Where a forward step goes when it is cancelled or runs out of time. The target assumes the step
   * may have partly happened: an authorization that timed out may exist at the provider, so the
   * order goes through {@code REVERSING_PAYMENT}, which looks before it acts.
   *
   * @throws IllegalStateException for states that are not forward steps
   */
  public OrderState compensation() {
    return switch (this) {
      case PENDING -> RELEASING_INVENTORY;
      case INVENTORY_RESERVED, PAYMENT_AUTHORIZED -> REVERSING_PAYMENT;
      case PAYMENT_CAPTURED -> CANCELLING_SHIPMENT;
      default -> throw new IllegalStateException(this + " has no compensation");
    };
  }
}
