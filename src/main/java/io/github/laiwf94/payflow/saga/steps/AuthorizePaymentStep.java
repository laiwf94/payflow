package io.github.laiwf94.payflow.saga.steps;

import io.github.laiwf94.payflow.gateway.CallResult;
import io.github.laiwf94.payflow.gateway.PaymentGateway;
import io.github.laiwf94.payflow.gateway.PaymentInfo;
import io.github.laiwf94.payflow.order.Order;
import io.github.laiwf94.payflow.order.OrderState;
import io.github.laiwf94.payflow.saga.SagaProperties;
import io.github.laiwf94.payflow.saga.SagaStep;
import io.github.laiwf94.payflow.saga.StepOutcome;
import org.springframework.stereotype.Component;

/** Holds the order amount on the customer's card. */
@Component
class AuthorizePaymentStep implements SagaStep {

  private final PaymentGateway payments;
  private final SagaProperties props;

  AuthorizePaymentStep(PaymentGateway payments, SagaProperties props) {
    this.payments = payments;
    this.props = props;
  }

  @Override
  public OrderState handles() {
    return OrderState.INVENTORY_RESERVED;
  }

  @Override
  public StepOutcome execute(Order order, boolean resumed) {
    String reference = order.id().toString();
    CallResult<PaymentInfo> result =
        payments.authorize(
            Keys.authorize(order.id()), reference, order.amount(), order.paymentMethod());
    return switch (result) {
      case CallResult.Success<PaymentInfo> s ->
          new StepOutcome.Advance(
              OrderState.PAYMENT_AUTHORIZED, StepOutcome.Fields.payment(s.value().id()));
      case CallResult.Rejected<PaymentInfo> r ->
          new StepOutcome.Fail(
              "payment_declined", "card declined: " + r.code(), OrderState.RELEASING_INVENTORY);
      case CallResult.Transient<PaymentInfo> t -> new StepOutcome.Retry(t.reason());
      case CallResult.Unknown<PaymentInfo> u ->
          new StepOutcome.Retry(u.reason(), props.unknownOutcomeDelay());
    };
  }
}
