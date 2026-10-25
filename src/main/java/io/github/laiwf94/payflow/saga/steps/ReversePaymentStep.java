package io.github.laiwf94.payflow.saga.steps;

import io.github.laiwf94.payflow.gateway.CallResult;
import io.github.laiwf94.payflow.gateway.PaymentGateway;
import io.github.laiwf94.payflow.gateway.PaymentInfo;
import io.github.laiwf94.payflow.gateway.PaymentStatus;
import io.github.laiwf94.payflow.order.Order;
import io.github.laiwf94.payflow.order.OrderState;
import io.github.laiwf94.payflow.saga.SagaProperties;
import io.github.laiwf94.payflow.saga.SagaStep;
import io.github.laiwf94.payflow.saga.StepOutcome;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Gives the customer's money back, whatever state the provider has it in. It works from the
 * provider's list of payments for the order, not from the order row, so it also undoes an
 * authorization or capture whose answer was lost, and every payment for the reference if more than
 * one exists: holds are voided, captures refunded.
 */
@Component
class ReversePaymentStep implements SagaStep {

  private final PaymentGateway payments;
  private final SagaProperties props;
  private final UnknownOutcomes unknown;

  ReversePaymentStep(PaymentGateway payments, SagaProperties props, UnknownOutcomes unknown) {
    this.payments = payments;
    this.props = props;
    this.unknown = unknown;
  }

  @Override
  public OrderState handles() {
    return OrderState.REVERSING_PAYMENT;
  }

  @Override
  public StepOutcome execute(Order order, boolean resumed) {
    CallResult<List<PaymentInfo>> found = payments.findByReference(order.id().toString());
    if (!(found instanceof CallResult.Success<List<PaymentInfo>> s)) {
      return new StepOutcome.Retry("payment lookup failed: " + found);
    }
    long captured = 0;
    long refunded = 0;
    for (PaymentInfo p : s.value()) {
      CallResult<PaymentInfo> r = null;
      if (p.status() == PaymentStatus.AUTHORIZED) {
        r = payments.voidAuthorization(Keys.voidPayment(p.id()), p.id());
      } else if (p.status() == PaymentStatus.CAPTURED && p.netCaptured() > 0) {
        r = payments.refund(Keys.reverse(p.id()), p.id(), p.netCaptured());
      }
      PaymentInfo now = p;
      if (r != null) {
        if (!(r instanceof CallResult.Success<PaymentInfo> ok)) {
          if (r instanceof CallResult.Unknown<PaymentInfo>) {
            unknown.seen("reverse");
          }
          return r instanceof CallResult.Unknown<PaymentInfo>
              ? new StepOutcome.Retry("reversal outcome unknown", props.unknownOutcomeDelay())
              : new StepOutcome.Retry("reversal failed: " + r);
        }
        now = ok.value();
      }
      captured += now.captured();
      refunded += now.refunded();
    }
    return new StepOutcome.Advance(
        OrderState.RELEASING_INVENTORY, StepOutcome.Fields.money(captured, refunded));
  }
}
