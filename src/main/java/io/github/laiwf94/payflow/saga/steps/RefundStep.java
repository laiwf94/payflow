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
 * Refunds a completed order in full at the merchant's request. Like the reversal it refunds
 * whatever the provider says is still captured, so a refund whose answer was lost isn't repeated.
 */
@Component
class RefundStep implements SagaStep {

  private final PaymentGateway payments;
  private final SagaProperties props;
  private final UnknownOutcomes unknown;

  RefundStep(PaymentGateway payments, SagaProperties props, UnknownOutcomes unknown) {
    this.payments = payments;
    this.props = props;
    this.unknown = unknown;
  }

  @Override
  public OrderState handles() {
    return OrderState.REFUNDING;
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
      PaymentInfo now = p;
      if (p.status() == PaymentStatus.CAPTURED && p.netCaptured() > 0) {
        CallResult<PaymentInfo> r = payments.refund(Keys.refund(p.id()), p.id(), p.netCaptured());
        if (!(r instanceof CallResult.Success<PaymentInfo> ok)) {
          if (r instanceof CallResult.Unknown<PaymentInfo>) {
            unknown.seen("refund");
          }
          return r instanceof CallResult.Unknown<PaymentInfo>
              ? new StepOutcome.Retry("refund outcome unknown", props.unknownOutcomeDelay())
              : new StepOutcome.Retry("refund failed: " + r);
        }
        now = ok.value();
      }
      captured += now.captured();
      refunded += now.refunded();
    }
    return new StepOutcome.Advance(
        OrderState.REFUNDED, StepOutcome.Fields.money(captured, refunded));
  }
}
