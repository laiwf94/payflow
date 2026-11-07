package io.github.laiwf94.payflow.refund;

import io.github.laiwf94.payflow.order.Order;

/**
 * One try at making a refund at the provider. Like a saga step it must be safe to run again: after
 * a crash or a lost answer the same refund is tried a second time, possibly after the first try
 * took effect, and is told so by {@code resumed}.
 */
@FunctionalInterface
public interface RefundAttempt {

  RefundOutcome execute(Refund refund, Order order, boolean resumed);
}
