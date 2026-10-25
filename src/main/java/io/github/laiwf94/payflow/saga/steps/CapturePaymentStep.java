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

/** Takes the authorized money. */
@Component
class CapturePaymentStep implements SagaStep {

  private final PaymentGateway payments;
  private final SagaProperties props;

  CapturePaymentStep(PaymentGateway payments, SagaProperties props) {
    this.payments = payments;
    this.props = props;
  }

  @Override
  public OrderState handles() {
    return OrderState.PAYMENT_AUTHORIZED;
  }

  @Override
  public StepOutcome execute(Order order, boolean resumed) {
    long amount = order.amount().minorUnits();
    return switch (payments.capture(Keys.capture(order.id()), order.paymentId(), amount)) {
      case CallResult.Success<PaymentInfo> s -> captured(s.value());
      case CallResult.Rejected<PaymentInfo> r ->
          new StepOutcome.Fail(
              "capture_failed",
              "provider refused the capture: " + r.code(),
              OrderState.REVERSING_PAYMENT);
      case CallResult.Transient<PaymentInfo> t -> new StepOutcome.Retry(t.reason());
      case CallResult.Unknown<PaymentInfo> u ->
          new StepOutcome.Retry(u.reason(), props.unknownOutcomeDelay());
    };
  }

  static StepOutcome captured(PaymentInfo p) {
    return new StepOutcome.Advance(
        OrderState.PAYMENT_CAPTURED, StepOutcome.Fields.money(p.captured(), p.refunded()));
  }
}
