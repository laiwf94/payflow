package io.github.laiwf94.payflow.order;

/** One product line. {@code unitAmount} is in the order currency's minor units. */
public record LineItem(String sku, int quantity, long unitAmount) {}
