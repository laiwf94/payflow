package io.github.laiwf94.payflow.saga.steps;

import io.github.laiwf94.payflow.gateway.CallResult;
import io.github.laiwf94.payflow.gateway.InventoryService;
import io.github.laiwf94.payflow.order.Order;
import io.github.laiwf94.payflow.order.OrderState;
import io.github.laiwf94.payflow.saga.SagaStep;
import io.github.laiwf94.payflow.saga.StepOutcome;
import org.springframework.stereotype.Component;

/**
 * Holds stock for every line. The reservation id is the order id, so a retry after a lost answer
 * finds the reservation it already made instead of taking stock twice.
 */
@Component
class ReserveInventoryStep implements SagaStep {

  private final InventoryService inventory;

  ReserveInventoryStep(InventoryService inventory) {
    this.inventory = inventory;
  }

  @Override
  public OrderState handles() {
    return OrderState.PENDING;
  }

  @Override
  public StepOutcome execute(Order order, boolean resumed) {
    return switch (inventory.reserve(order.id().toString(), order.items())) {
      case CallResult.Success<String> s ->
          new StepOutcome.Advance(
              OrderState.INVENTORY_RESERVED, StepOutcome.Fields.reservation(s.value()));
      case CallResult.Rejected<String> r ->
          new StepOutcome.Fail(
              "out_of_stock".equals(r.code()) ? "out_of_stock" : "inventory_rejected",
              r.message(),
              OrderState.RELEASING_INVENTORY);
      case CallResult.Transient<String> t -> new StepOutcome.Retry(t.reason());
      case CallResult.Unknown<String> u -> new StepOutcome.Retry(u.reason());
    };
  }
}
