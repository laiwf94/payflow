package io.github.laiwf94.payflow.saga;

import io.github.laiwf94.payflow.order.Order;
import io.github.laiwf94.payflow.order.OrderState;

/**
 * The work done while an order is in one state. Implementations call one or more dependencies and
 * must be safe to run again: after a crash or timeout the same step runs a second time for the same
 * order, possibly after the first run already took effect.
 */
public interface SagaStep {

  /** The state this step moves an order out of. */
  OrderState handles();

  StepOutcome execute(Order order, boolean resumed);
}
