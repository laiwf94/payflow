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
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Takes the authorized money. On a retry or resume it asks the provider first: if the earlier
 * capture went through, capturing again would be refused (or, at a provider without idempotency
 * keys, could charge twice), and the order would be failed for a capture that actually succeeded.
 */
@Component
class CapturePaymentStep implements SagaStep {

  private final PaymentGateway payments;
  private final SagaProperties props;
  private final UnknownOutcomes unknown;

  CapturePaymentStep(PaymentGateway payments, SagaProperties props, UnknownOutcomes unknown) {
    this.payments = payments;
    this.props = props;
    this.unknown = unknown;
  }

  @Override
  public OrderState handles() {
    return OrderState.PAYMENT_AUTHORIZED;
  }

  @Override
  public StepOutcome execute(Order order, boolean resumed) {
    if (resumed) {
      CallResult<List<PaymentInfo>> found = payments.findByReference(order.id().toString());
      if (!(found instanceof CallResult.Success<List<PaymentInfo>> s)) {
        return new StepOutcome.Retry(
            "payment lookup failed: " + found, props.unknownOutcomeDelay());
      }
      Optional<PaymentInfo> ours =
          s.value().stream().filter(p -> p.id().equals(order.paymentId())).findFirst();
      if (ours.isPresent() && ours.get().status() == PaymentStatus.CAPTURED) {
        unknown.resolved("capture", true);
        return captured(ours.get());
      }
      if (ours.isEmpty() || ours.get().status() != PaymentStatus.AUTHORIZED) {
        return refused(
            ours.map(p -> p.status().name().toLowerCase(java.util.Locale.ROOT)).orElse("missing"));
      }
      unknown.resolved("capture", false);
    }
    long amount = order.amount().minorUnits();
    return switch (payments.capture(Keys.capture(order.id()), order.paymentId(), amount)) {
      case CallResult.Success<PaymentInfo> s -> captured(s.value());
      case CallResult.Rejected<PaymentInfo> r -> refused(r.code());
      case CallResult.Transient<PaymentInfo> t -> new StepOutcome.Retry(t.reason());
      case CallResult.Unknown<PaymentInfo> u -> {
        unknown.seen("capture");
        yield new StepOutcome.Retry(u.reason(), props.unknownOutcomeDelay());
      }
    };
  }

  private static StepOutcome captured(PaymentInfo p) {
    return new StepOutcome.Advance(
        OrderState.PAYMENT_CAPTURED, StepOutcome.Fields.money(p.captured(), p.refunded()));
  }

  private static StepOutcome refused(String why) {
    return new StepOutcome.Fail(
        "capture_failed", "provider refused the capture: " + why, OrderState.REVERSING_PAYMENT);
  }
}
