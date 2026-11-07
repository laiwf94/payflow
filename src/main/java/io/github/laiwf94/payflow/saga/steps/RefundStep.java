package io.github.laiwf94.payflow.saga.steps;

import io.github.laiwf94.payflow.gateway.CallResult;
import io.github.laiwf94.payflow.gateway.PaymentGateway;
import io.github.laiwf94.payflow.gateway.RefundInfo;
import io.github.laiwf94.payflow.order.Order;
import io.github.laiwf94.payflow.refund.Refund;
import io.github.laiwf94.payflow.refund.RefundAttempt;
import io.github.laiwf94.payflow.refund.RefundOutcome;
import io.github.laiwf94.payflow.saga.SagaProperties;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Makes one refund at the provider. It asks first: the provider lists the payment's refunds with
 * the reference each was made under (the refund row's id), so a refund whose answer was lost, or
 * whose worker died mid-call, is found and recorded instead of made again. That holds at a provider
 * that ignores idempotency keys too; the key, one per refund row, covers the window between the
 * lookup and the call at one that honours them.
 */
@Component
public class RefundStep implements RefundAttempt {

  private final PaymentGateway payments;
  private final SagaProperties props;
  private final UnknownOutcomes unknown;

  RefundStep(PaymentGateway payments, SagaProperties props, UnknownOutcomes unknown) {
    this.payments = payments;
    this.props = props;
    this.unknown = unknown;
  }

  /**
   * One attempt at {@code refund} of {@code order}. {@code resumed} says an earlier attempt may
   * have reached the provider; it only decides how the lookup is counted.
   */
  @Override
  public RefundOutcome execute(Refund refund, Order order, boolean resumed) {
    if (order.paymentId() == null) {
      return new RefundOutcome.Failed("no_payment", "order has no captured payment to refund");
    }
    String reference = refund.id().toString();
    CallResult<List<RefundInfo>> found = payments.findRefunds(order.paymentId());
    if (!(found instanceof CallResult.Success<List<RefundInfo>> s)) {
      return new RefundOutcome.Retry("refund lookup failed: " + found);
    }
    Optional<RefundInfo> made =
        s.value().stream().filter(r -> reference.equals(r.reference())).findFirst();
    if (resumed) {
      unknown.resolved("refund", made.isPresent());
    }
    if (made.isPresent()) {
      return new RefundOutcome.Succeeded(made.get().id());
    }
    return switch (payments.refund(
        Keys.refund(refund.id()), order.paymentId(), refund.amountMinor(), reference)) {
      case CallResult.Success<RefundInfo> ok -> new RefundOutcome.Succeeded(ok.value().id());
      case CallResult.Rejected<RefundInfo> r ->
          new RefundOutcome.Failed(
              "provider_refused", "provider refused the refund: " + r.code() + " " + r.message());
      case CallResult.Transient<RefundInfo> t -> new RefundOutcome.Retry(t.reason());
      case CallResult.Unknown<RefundInfo> u -> {
        unknown.seen("refund");
        yield new RefundOutcome.Retry(u.reason(), props.unknownOutcomeDelay());
      }
    };
  }
}
