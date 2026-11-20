package io.github.laiwf94.payflow.saga.steps;

import io.github.laiwf94.payflow.gateway.CallResult;
import io.github.laiwf94.payflow.gateway.PaymentGateway;
import io.github.laiwf94.payflow.gateway.PaymentInfo;
import io.github.laiwf94.payflow.gateway.PaymentStatus;
import io.github.laiwf94.payflow.hold.HoldProperties;
import io.github.laiwf94.payflow.order.Order;
import io.github.laiwf94.payflow.order.OrderState;
import io.github.laiwf94.payflow.saga.SagaProperties;
import io.github.laiwf94.payflow.saga.StepOutcome;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Extends the authorization of a manual-capture order that is still waiting for the merchant, once,
 * when the {@code order.reauthorization} timer asked for it shortly before the hold lapses. It runs
 * inside the capture step of {@code PAYMENT_AUTHORIZED}, since the order doesn't change state: on
 * success it goes back to {@linkplain StepOutcome.Await waiting} for the capture, now until the
 * expiry the provider reports for the extended authorization, and records that and when it was
 * extended. An expiry the provider doesn't report is estimated as for a new authorization, from
 * when this step started, so it is never later than the real one.
 *
 * <p>If the issuer has already dropped the hold, early, there is nothing left to extend: the order
 * fails as {@code authorization_expired} into the reversal, which releases the stock. Any other
 * refusal fails it as {@code reauthorization_failed}: the old authorization may have hours left,
 * but an order that can't be extended would only fail a little later, after the merchant may have
 * shipped on the strength of it.
 *
 * <p>On a retry or resume it asks the provider first: an extension whose answer was lost shows as
 * an expiry later than the one recorded, and the order takes that one rather than extending again.
 */
@Component
class ReauthorizeStep {

  /** The provider's code for a capture or extension of a hold that has lapsed. */
  static final String EXPIRED = "authorization_expired";

  private final PaymentGateway payments;
  private final SagaProperties props;
  private final UnknownOutcomes unknown;
  private final HoldProperties holds;

  ReauthorizeStep(
      PaymentGateway payments,
      SagaProperties props,
      UnknownOutcomes unknown,
      HoldProperties holds) {
    this.payments = payments;
    this.props = props;
    this.unknown = unknown;
    this.holds = holds;
  }

  StepOutcome execute(Order order, boolean resumed) {
    if (resumed) {
      CallResult<List<PaymentInfo>> found = payments.findByReference(order.id().toString());
      if (!(found instanceof CallResult.Success<List<PaymentInfo>> s)) {
        return new StepOutcome.Retry(
            "payment lookup failed: " + found, props.unknownOutcomeDelay());
      }
      Optional<PaymentInfo> ours =
          s.value().stream().filter(p -> p.id().equals(order.paymentId())).findFirst();
      if (ours.isEmpty()) {
        return refused("missing");
      }
      PaymentInfo p = ours.get();
      if (p.status() == PaymentStatus.EXPIRED) {
        unknown.resolved("reauthorize", false);
        return lapsed();
      }
      if (p.status() != PaymentStatus.AUTHORIZED) {
        return refused(p.status().name().toLowerCase(Locale.ROOT));
      }
      if (p.expiresAt() != null
          && order.authorizationExpiresAt() != null
          && p.expiresAt().isAfter(order.authorizationExpiresAt())) {
        unknown.resolved("reauthorize", true);
        return extended(order, p);
      }
      unknown.resolved("reauthorize", false);
    }
    return switch (payments.reauthorize(Keys.reauthorize(order.id()), order.paymentId())) {
      case CallResult.Success<PaymentInfo> s -> extended(order, s.value());
      case CallResult.Rejected<PaymentInfo> r ->
          EXPIRED.equals(r.code()) ? lapsed() : refused(r.code());
      case CallResult.Transient<PaymentInfo> t -> new StepOutcome.Retry(t.reason());
      case CallResult.Unknown<PaymentInfo> u -> {
        unknown.seen("reauthorize");
        yield new StepOutcome.Retry(u.reason(), props.unknownOutcomeDelay());
      }
    };
  }

  private StepOutcome extended(Order order, PaymentInfo p) {
    Instant started = order.stepStartedAt();
    Instant expires =
        p.expiresAt() != null ? p.expiresAt() : started.plus(holds.authorizationLifetime());
    return new StepOutcome.Await(
        "re-authorized; waiting for the merchant to capture",
        expires,
        CapturePaymentStep.authorizationExpired(),
        StepOutcome.Fields.reauthorization(expires, started));
  }

  private static StepOutcome lapsed() {
    return new StepOutcome.Fail(
        "authorization_expired",
        "the authorization lapsed at the issuer before it could be extended",
        OrderState.REVERSING_PAYMENT);
  }

  private static StepOutcome refused(String why) {
    return new StepOutcome.Fail(
        "reauthorization_failed",
        "provider refused to extend the authorization: " + why,
        OrderState.REVERSING_PAYMENT);
  }
}
