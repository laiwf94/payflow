package io.github.laiwf94.payflow.saga.steps;

import io.github.laiwf94.payflow.gateway.CallResult;
import io.github.laiwf94.payflow.gateway.ShipmentInfo;
import io.github.laiwf94.payflow.gateway.ShippingService;
import io.github.laiwf94.payflow.order.Order;
import io.github.laiwf94.payflow.order.OrderState;
import io.github.laiwf94.payflow.saga.SagaStep;
import io.github.laiwf94.payflow.saga.StepOutcome;
import org.springframework.stereotype.Component;

/**
 * Books the shipment. The carrier keeps one shipment per reference, so a retry after a lost answer
 * returns the shipment that was already created.
 */
@Component
class CreateShipmentStep implements SagaStep {

  private final ShippingService shipping;

  CreateShipmentStep(ShippingService shipping) {
    this.shipping = shipping;
  }

  @Override
  public OrderState handles() {
    return OrderState.PAYMENT_CAPTURED;
  }

  @Override
  public StepOutcome execute(Order order, boolean resumed) {
    return switch (shipping.create(order.id().toString(), order.shippingAddress())) {
      case CallResult.Success<ShipmentInfo> s ->
          new StepOutcome.Advance(
              OrderState.COMPLETED,
              StepOutcome.Fields.shipment(s.value().id(), s.value().trackingNumber()));
      case CallResult.Rejected<ShipmentInfo> r ->
          new StepOutcome.Fail(
              "shipping_failed",
              "carrier refused the shipment: " + r.code(),
              OrderState.CANCELLING_SHIPMENT);
      case CallResult.Transient<ShipmentInfo> t -> new StepOutcome.Retry(t.reason());
      case CallResult.Unknown<ShipmentInfo> u -> new StepOutcome.Retry(u.reason());
    };
  }
}
