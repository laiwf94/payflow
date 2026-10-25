package io.github.laiwf94.payflow.saga.steps;

import io.github.laiwf94.payflow.gateway.CallResult;
import io.github.laiwf94.payflow.gateway.ShipmentInfo;
import io.github.laiwf94.payflow.gateway.ShippingService;
import io.github.laiwf94.payflow.order.Order;
import io.github.laiwf94.payflow.order.OrderState;
import io.github.laiwf94.payflow.saga.SagaStep;
import io.github.laiwf94.payflow.saga.StepOutcome;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Cancels the shipment if the carrier has one. It asks the carrier rather than trusting the order
 * row: a create call that timed out may still have produced a label.
 */
@Component
class CancelShipmentStep implements SagaStep {

  private final ShippingService shipping;

  CancelShipmentStep(ShippingService shipping) {
    this.shipping = shipping;
  }

  @Override
  public OrderState handles() {
    return OrderState.CANCELLING_SHIPMENT;
  }

  @Override
  public StepOutcome execute(Order order, boolean resumed) {
    CallResult<Optional<ShipmentInfo>> found = shipping.findByReference(order.id().toString());
    if (!(found instanceof CallResult.Success<Optional<ShipmentInfo>> s)) {
      return new StepOutcome.Retry("shipment lookup failed: " + found);
    }
    if (s.value().isEmpty() || s.value().get().cancelled()) {
      return new StepOutcome.Advance(OrderState.REVERSING_PAYMENT);
    }
    return switch (shipping.cancel(s.value().get().id())) {
      case CallResult.Success<ShipmentInfo> c ->
          new StepOutcome.Advance(OrderState.REVERSING_PAYMENT);
      case CallResult.Rejected<ShipmentInfo> r ->
          "not_found".equals(r.code())
              ? new StepOutcome.Advance(OrderState.REVERSING_PAYMENT)
              : new StepOutcome.Retry("carrier refused the cancellation: " + r.code());
      case CallResult.Transient<ShipmentInfo> t -> new StepOutcome.Retry(t.reason());
      case CallResult.Unknown<ShipmentInfo> u -> new StepOutcome.Retry(u.reason());
    };
  }
}
