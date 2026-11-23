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
import io.github.laiwf94.payflow.saga.SagaStep;
import io.github.laiwf94.payflow.saga.StepOutcome;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Holds the order amount on the customer's payment methods, one tender at a time in position order,
 * each under its own idempotency key and provider reference.
 *
 * <p>Each authorized tender is recorded before the next is tried ({@link StepOutcome.Progress}), so
 * a retry or a resume after a crash carries on with the first tender that is still pending; the
 * order moves on once the last one is held. A tender that is declined fails the order: if earlier
 * tenders already hold money it goes to {@code REVERSING_PAYMENT}, which voids them in reverse
 * order, and otherwise straight to releasing the stock.
 *
 * <p>When the step runs again for a pending tender, the earlier attempt may have gone through
 * without us hearing about it. It asks the provider first and only authorizes again if nothing is
 * there. The idempotency key alone would cover a provider that honours keys; the lookup also covers
 * one that doesn't, or a key that expired. An authorization the lookup finds already lapsed ({@link
 * PaymentStatus#EXPIRED}, which an issuer can do before the expiry it advertised) is not made
 * again: the order fails as {@code authorization_expired}, as it would if the capture had found the
 * hold gone, and the customer is never authorized twice.
 *
 * <p>It records when each authorization lapses: the expiry the provider reports for it, or, from a
 * provider that reports none, {@link HoldProperties#authorizationLifetime()} after the step
 * started. Every attempt, and so the authorization itself, happened at or after that, so the
 * estimate is never later than the provider's. The order keeps the earliest of them.
 */
@Component
class AuthorizePaymentStep implements SagaStep {

  private final PaymentGateway payments;
  private final PaymentRepository tenders;
  private final SagaProperties props;
  private final UnknownOutcomes unknown;
  private final HoldProperties holds;

  AuthorizePaymentStep(
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

  @Override
  public OrderState handles() {
    return OrderState.INVENTORY_RESERVED;
  }

  @Override
  public StepOutcome execute(Order order, boolean resumed) {
    Tenders all = Tenders.of(tenders, order);
    Optional<Payment> unexpected =
        all.first(p -> p.state() != PaymentState.PENDING && p.state() != PaymentState.AUTHORIZED);
    if (unexpected.isPresent()) {
      throw new IllegalStateException(
          "tender " + unexpected.get().position() + " is " + unexpected.get().state().code());
    }
    Optional<Payment> next = all.first(p -> p.state() == PaymentState.PENDING);
    if (next.isEmpty()) {
      // Only an order from before tenders were recorded one by one can get here.
      return new StepOutcome.Advance(OrderState.PAYMENT_AUTHORIZED, all.authorization());
    }
    Payment t = next.get();
    if (resumed) {
      CallResult<List<PaymentInfo>> found = payments.findByReference(t.providerReference());
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
        return authorized(order, all, t, held.get());
      }
      Optional<PaymentInfo> lapsed =
          s.value().stream().filter(p -> p.status() == PaymentStatus.EXPIRED).findFirst();
      if (lapsed.isPresent()) {
        unknown.resolved("authorize", true);
        return failed(
            all,
            t,
            PaymentState.EXPIRED,
            ReauthorizeStep.EXPIRED,
            "the authorization lapsed before its answer was found: " + lapsed.get().id());
      }
      Optional<PaymentInfo> declined =
          s.value().stream().filter(p -> p.status() == PaymentStatus.DECLINED).findFirst();
      if (declined.isPresent()) {
        unknown.resolved("authorize", true);
        return declined(all, t, declined.get().declineCode());
      }
      unknown.resolved("authorize", false);
    }
    CallResult<PaymentInfo> result =
        payments.authorize(t.authorizeKey(), t.providerReference(), t.amount(), t.method());
    return switch (result) {
      case CallResult.Success<PaymentInfo> s -> authorized(order, all, t, s.value());
      case CallResult.Rejected<PaymentInfo> r -> declined(all, t, r.code());
      case CallResult.Transient<PaymentInfo> t2 -> new StepOutcome.Retry(t2.reason());
      case CallResult.Unknown<PaymentInfo> u -> {
        unknown.seen("authorize");
        yield new StepOutcome.Retry(u.reason(), props.unknownOutcomeDelay());
      }
    };
  }

  private StepOutcome authorized(Order order, Tenders all, Payment t, PaymentInfo p) {
    Instant expires =
        p.expiresAt() != null
            ? p.expiresAt()
            : order.stepStartedAt().plus(holds.authorizationLifetime());
    Payment done = t.authorized(p.id(), expires);
    Tenders after = all.with(done);
    if (after.any(x -> x.state() == PaymentState.PENDING)) {
      return new StepOutcome.Progress(after.authorization(), List.of(done));
    }
    return new StepOutcome.Advance(
        OrderState.PAYMENT_AUTHORIZED, after.authorization(), List.of(done));
  }

  private static StepOutcome declined(Tenders all, Payment t, String code) {
    return failed(
        all,
        t,
        PaymentState.DECLINED,
        "payment_declined",
        (all.all().size() == 1 ? "card declined: " : "tender " + t.position() + " declined: ")
            + code);
  }

  /**
   * Fails the order on tender {@code t}, which ends {@code state}. Tenders authorized before it
   * still hold money, so they are reversed first; with none, only the stock is left to release.
   */
  private static StepOutcome failed(
      Tenders all, Payment t, PaymentState state, String code, String message) {
    Payment ended = t.settled(null, 0, 0, state);
    Tenders after = all.with(ended);
    OrderState next =
        after.any(Payment::holdsMoney)
            ? OrderState.REVERSING_PAYMENT
            : OrderState.RELEASING_INVENTORY;
    return new StepOutcome.Fail(code, message, next, StepOutcome.Fields.NONE, List.of(ended));
  }
}
