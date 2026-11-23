package io.github.laiwf94.payflow.saga.steps;

import io.github.laiwf94.payflow.gateway.CallResult;
import io.github.laiwf94.payflow.gateway.PaymentGateway;
import io.github.laiwf94.payflow.gateway.PaymentInfo;
import io.github.laiwf94.payflow.gateway.PaymentStatus;
import io.github.laiwf94.payflow.hold.HoldProperties;
import io.github.laiwf94.payflow.order.Order;
import io.github.laiwf94.payflow.order.OrderState;
import io.github.laiwf94.payflow.payment.Payment;
import io.github.laiwf94.payflow.payment.PaymentRepository;
import io.github.laiwf94.payflow.payment.PaymentState;
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
 * <p>Every tender's authorization is extended, in position order under its own key, and each is
 * recorded before the next ({@link StepOutcome.Progress}). The order then waits until the earliest
 * of the new expiries.
 *
 * <p>On a retry or resume it asks the provider first: an extension whose answer was lost shows as
 * an expiry later than the one recorded, and the tender takes that one rather than extending again.
 */
@Component
class ReauthorizeStep {

  /** The provider's code for a capture or extension of a hold that has lapsed. */
  static final String EXPIRED = "authorization_expired";

  private final PaymentGateway payments;
  private final PaymentRepository tenders;
  private final SagaProperties props;
  private final UnknownOutcomes unknown;
  private final HoldProperties holds;

  ReauthorizeStep(
      PaymentGateway payments,
      PaymentRepository tenders,
      SagaProperties props,
      UnknownOutcomes unknown,
      HoldProperties holds) {
    this.payments = payments;
    this.tenders = tenders;
    this.props = props;
    this.unknown = unknown;
    this.holds = holds;
  }

  StepOutcome execute(Order order, boolean resumed) {
    Tenders all = Tenders.of(tenders, order);
    Optional<Payment> next =
        all.first(p -> p.state() == PaymentState.AUTHORIZED && p.reauthorizedAt() == null);
    if (next.isEmpty()) {
      return waitAgain(order, all, List.of());
    }
    Payment t = next.get();
    Instant recorded =
        t.authorizationExpiresAt() != null
            ? t.authorizationExpiresAt()
            : order.authorizationExpiresAt();
    if (resumed) {
      CallResult<List<PaymentInfo>> found = payments.findByReference(t.providerReference());
      if (!(found instanceof CallResult.Success<List<PaymentInfo>> s)) {
        return new StepOutcome.Retry(
            "payment lookup failed: " + found, props.unknownOutcomeDelay());
      }
      Optional<PaymentInfo> ours =
          s.value().stream().filter(p -> p.id().equals(t.providerPaymentId())).findFirst();
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
      if (p.expiresAt() != null && recorded != null && p.expiresAt().isAfter(recorded)) {
        unknown.resolved("reauthorize", true);
        return extended(order, all, t, p);
      }
      unknown.resolved("reauthorize", false);
    }
    return switch (payments.reauthorize(t.reauthorizeKey(), t.providerPaymentId())) {
      case CallResult.Success<PaymentInfo> s -> extended(order, all, t, s.value());
      case CallResult.Rejected<PaymentInfo> r ->
          EXPIRED.equals(r.code()) ? lapsed() : refused(r.code());
      case CallResult.Transient<PaymentInfo> t2 -> new StepOutcome.Retry(t2.reason());
      case CallResult.Unknown<PaymentInfo> u -> {
        unknown.seen("reauthorize");
        yield new StepOutcome.Retry(u.reason(), props.unknownOutcomeDelay());
      }
    };
  }

  private StepOutcome extended(Order order, Tenders all, Payment t, PaymentInfo p) {
    Instant started = order.stepStartedAt();
    Instant expires =
        p.expiresAt() != null ? p.expiresAt() : started.plus(holds.authorizationLifetime());
    Payment done = t.reauthorized(expires, started);
    Tenders after = all.with(done);
    if (after.any(x -> x.state() == PaymentState.AUTHORIZED && x.reauthorizedAt() == null)) {
      return new StepOutcome.Progress(StepOutcome.Fields.NONE, List.of(done));
    }
    return waitAgain(order, after, List.of(done));
  }

  /** Every tender is extended: wait for the capture until the first of them lapses. */
  private StepOutcome waitAgain(Order order, Tenders all, List<Payment> changed) {
    Instant started = order.stepStartedAt();
    Instant expires = all.earliestExpiry();
    if (expires == null) {
      expires = started.plus(holds.authorizationLifetime());
    }
    return new StepOutcome.Await(
        "re-authorized; waiting for the merchant to capture",
        expires,
        CapturePaymentStep.authorizationExpired(),
        StepOutcome.Fields.reauthorization(expires, started),
        changed);
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
