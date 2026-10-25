package io.github.laiwf94.payflow.saga.steps;

import io.github.laiwf94.payflow.gateway.CallResult;
import io.github.laiwf94.payflow.gateway.InventoryService;
import io.github.laiwf94.payflow.order.Order;
import io.github.laiwf94.payflow.order.OrderState;
import io.github.laiwf94.payflow.saga.SagaStep;
import io.github.laiwf94.payflow.saga.StepOutcome;
import org.springframework.stereotype.Component;

/**
 * Releases the stock and ends the saga. The order ends {@code CANCELLED} when the merchant asked
 * for it and nothing else had failed first, and {@code FAILED} otherwise.
 */
@Component
class ReleaseInventoryStep implements SagaStep {

  private final InventoryService inventory;

  ReleaseInventoryStep(InventoryService inventory) {
    this.inventory = inventory;
  }

  @Override
  public OrderState handles() {
    return OrderState.RELEASING_INVENTORY;
  }

  @Override
  public StepOutcome execute(Order order, boolean resumed) {
    return switch (inventory.release(order.id().toString())) {
      case CallResult.Success<Boolean> s ->
          new StepOutcome.Advance(
              order.cancelRequested() && order.failureCode() == null
                  ? OrderState.CANCELLED
                  : OrderState.FAILED);
      case CallResult.Rejected<Boolean> r ->
          new StepOutcome.Retry("inventory refused the release: " + r.code());
      case CallResult.Transient<Boolean> t -> new StepOutcome.Retry(t.reason());
      case CallResult.Unknown<Boolean> u -> new StepOutcome.Retry(u.reason());
    };
  }
}
