package io.github.laiwf94.payflow.refund;

/** Some of one order line given back: {@code quantity} units of {@code sku}. */
public record RefundLine(String sku, int quantity) {}
