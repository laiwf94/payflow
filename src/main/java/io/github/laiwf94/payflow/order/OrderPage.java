package io.github.laiwf94.payflow.order;

import java.util.List;

/**
 * One page of a listing: up to the requested number of orders, and whether more follow the last.
 */
public record OrderPage(List<Order> orders, boolean hasMore) {

  public OrderPage {
    orders = List.copyOf(orders);
  }

  /** Where the next page starts, or null when this is the last. */
  public OrderPosition next() {
    return hasMore ? OrderPosition.of(orders.getLast()) : null;
  }
}
