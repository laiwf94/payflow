package io.github.laiwf94.payflow.refund;

import io.github.laiwf94.payflow.order.Order;

/** An order and its refunds, read together so that each agrees with the other. */
public record OrderRefunds(Order order, RefundSummary refunds) {}
