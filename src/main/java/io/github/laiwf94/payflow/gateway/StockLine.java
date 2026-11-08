package io.github.laiwf94.payflow.gateway;

/** {@code quantity} units of {@code sku}, as the inventory system counts them. */
public record StockLine(String sku, int quantity) {}
