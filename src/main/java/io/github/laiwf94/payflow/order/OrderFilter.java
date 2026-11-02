package io.github.laiwf94.payflow.order;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Which of a merchant's orders a listing returns. Every field is optional: an empty {@code states}
 * means any state, and a null bound or value means no constraint on it.
 *
 * @param states the states to include, or empty for all
 * @param createdFrom inclusive lower bound on {@code created_at}
 * @param createdBefore exclusive upper bound on {@code created_at}
 * @param currency ISO 4217 code
 * @param merchantReference the merchant's own reference, which names at most one order
 */
public record OrderFilter(
    Set<OrderState> states,
    Instant createdFrom,
    Instant createdBefore,
    String currency,
    String merchantReference) {

  public static final OrderFilter ALL = new OrderFilter(Set.of(), null, null, null, null);

  public OrderFilter {
    states = states.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(states));
    if (createdFrom != null && createdBefore != null && !createdFrom.isBefore(createdBefore)) {
      throw new IllegalArgumentException("created_gte must be before created_lt");
    }
  }

  /** Whether {@code o} belongs in this listing, ignoring the merchant. */
  public boolean matches(Order o) {
    return (states.isEmpty() || states.contains(o.state()))
        && (createdFrom == null || !o.createdAt().isBefore(createdFrom))
        && (createdBefore == null || o.createdAt().isBefore(createdBefore))
        && (currency == null || currency.equals(o.amount().currencyCode()))
        && (merchantReference == null || merchantReference.equals(o.merchantReference()));
  }

  /**
   * A stable text form, the same for equal filters whatever order the states were given in. A
   * cursor is signed over it, so a cursor only continues the listing it came from.
   */
  public String canonical() {
    return "states="
        + states.stream().map(Enum::name).sorted().collect(Collectors.joining(","))
        + "\ncreated_gte="
        + (createdFrom == null ? "" : createdFrom)
        + "\ncreated_lt="
        + (createdBefore == null ? "" : createdBefore)
        + "\ncurrency="
        + (currency == null ? "" : currency)
        + "\nmerchant_reference="
        + (merchantReference == null ? "" : merchantReference);
  }
}
