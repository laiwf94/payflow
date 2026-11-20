package io.github.laiwf94.payflow.saga.steps;

import io.github.laiwf94.payflow.gateway.CallResult;
import io.github.laiwf94.payflow.gateway.PaymentGateway;
import io.github.laiwf94.payflow.gateway.PaymentInfo;
import io.github.laiwf94.payflow.gateway.PaymentStatus;
import io.github.laiwf94.payflow.hold.HoldProperties;
import io.github.laiwf94.payflow.order.Order;
import io.github.laiwf94.payflow.order.OrderState;
import io.github.laiwf94.payflow.saga.SagaProperties;
import io.github.laiwf94.payflow.saga.SagaStep;
import io.github.laiwf94.payflow.saga.StepOutcome;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Holds the order amount on the customer's card.
 *
 * <p>When the step runs again (a retry, or a resume after a crash) the earlier attempt may have
 * gone through without us hearing about it. It asks the provider first and only authorizes again if
 * nothing is there. The idempotency key alone would cover a provider that honours keys; the lookup
 * also covers one that doesn't, or a key that expired.
 *
 * <p>It records when the authorization lapses: the expiry the provider reports for it, or, from a
 * provider that reports none, {@link HoldProperties#authorizationLifetime()} after the step
 * started. Every attempt, and so the authorization itself, happened at or after that, so the
 * estimate is never later than the provider's.
 */
@Component
class AuthorizePaymentStep implements SagaStep {

  private final PaymentGateway payments;
  private final SagaProperties props;
  private final UnknownOutcomes unknown;
  private final HoldProperties holds;

  AuthorizePaymentStep(
      PaymentGateway payments,
      SagaProperties props,
      UnknownOutcomes unknown,
      HoldProperties holds) {
    this.payments = payments;
    this.props = props;
    this.unknown = unknown;
    this.holds = holds;
  }

  @Override
  public OrderState handles() {
    return OrderState.INVENTORY_RESERVED;
  }

  @Override
  public StepOutcome execute(Order order, boolean resumed) {
    String reference = order.id().toString();
    if (resumed) {
      CallResult<List<PaymentInfo>> found = payments.findByReference(reference);
      if (!(found instanceof CallResult.Success<List<PaymentInfo>> s)) {
        return new StepOutcome.Retry(
            "payment lookup failed: " + found, props.unknownOutcomeDelay());
      }
      Optional<PaymentInfo> held =
          s.value().stream()
              .filter(
                  p ->
                      p.status() == PaymentStatus.AUTHORIZED
                          || p.status() == PaymentStatus.CAPTURED)
              .findFirst();
      if (held.isPresent()) {
        unknown.resolved("authorize", true);
        return authorized(order, held.get());
      }
      Optional<PaymentInfo> declined =
          s.value().stream().filter(p -> p.status() == PaymentStatus.DECLINED).findFirst();
      if (declined.isPresent()) {
        unknown.resolved("authorize", true);
        return declined(declined.get().declineCode());
      }
      unknown.resolved("authorize", false);
    }
    CallResult<PaymentInfo> result =
        payments.authorize(
            Keys.authorize(order.id()), reference, order.amount(), order.paymentMethod());
    return switch (result) {
      case CallResult.Success<PaymentInfo> s -> authorized(order, s.value());
      case CallResult.Rejected<PaymentInfo> r -> declined(r.code());
      case CallResult.Transient<PaymentInfo> t -> new StepOutcome.Retry(t.reason());
      case CallResult.Unknown<PaymentInfo> u -> {
        unknown.seen("authorize");
        yield new StepOutcome.Retry(u.reason(), props.unknownOutcomeDelay());
      }
    };
  }

  private StepOutcome authorized(Order order, PaymentInfo p) {
    Instant expires =
        p.expiresAt() != null
            ? p.expiresAt()
            : order.stepStartedAt().plus(holds.authorizationLifetime());
    return new StepOutcome.Advance(
        OrderState.PAYMENT_AUTHORIZED, StepOutcome.Fields.authorization(p.id(), expires));
  }

  private static StepOutcome declined(String code) {
    return new StepOutcome.Fail(
        "payment_declined", "card declined: " + code, OrderState.RELEASING_INVENTORY);
  }
}
