package io.github.laiwf94.payflow.saga.steps;

import io.github.laiwf94.payflow.gateway.CallResult;
import io.github.laiwf94.payflow.gateway.PaymentInfo;
import io.github.laiwf94.payflow.gateway.PaymentProviders;
import io.github.laiwf94.payflow.gateway.PaymentStatus;
import io.github.laiwf94.payflow.hold.HoldProperties;
import io.github.laiwf94.payflow.order.CaptureMode;
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
 * Takes the authorized money, tender by tender in position order, each under its own capture key,
 * on one of two paths.
 *
 * <ul>
 *   <li><b>Automatic</b>: captures straight away.
 *   <li><b>Manual</b>: until the merchant asks for the capture, there is nothing to do, so the
 *       order {@linkplain StepOutcome.Await waits}, unleased and off the due list, until the
 *       authorization expires. The request (or a cancel) makes it due at once; an expiry with no
 *       request fails it as {@code authorization_expired} into the reversal, which voids the
 *       authorization and releases the stock. Once asked, it captures exactly as the automatic path
 *       does. Shortly before the authorization lapses a timer may ask for it to be extended; the
 *       {@link ReauthorizeStep} does that, once, and the order goes back to waiting until the new
 *       expiry.
 * </ul>
 *
 * <p>Each captured tender is recorded before the next ({@link StepOutcome.Progress}), so a retry or
 * resume carries on with the first tender still only authorized. If one can't be captured, the
 * order goes to {@code REVERSING_PAYMENT}, which refunds the tenders already captured and voids the
 * rest, last tender first.
 *
 * <p>A provider that refuses the capture because the hold already lapsed (issuers sometimes drop
 * one before the expiry the provider advertised) fails the order as {@code authorization_expired}:
 * nothing was taken, and a lapsed authorization can't be extended.
 *
 * <p>On a retry or resume it asks the provider first: if the earlier capture went through,
 * capturing again would be refused (or, at a provider without idempotency keys, could charge
 * twice), and the order would be failed for a capture that actually succeeded. A manual order that
 * was never asked to capture has made no capture call, so it just waits again.
 */
@Component
class CapturePaymentStep implements SagaStep {

  private final PaymentProviders providers;
  private final PaymentRepository tenders;
  private final SagaProperties props;
  private final UnknownOutcomes unknown;
  private final HoldProperties holds;
  private final ReauthorizeStep reauthorize;

  CapturePaymentStep(
      PaymentProviders providers,
      PaymentRepository tenders,
      SagaProperties props,
      UnknownOutcomes unknown,
      HoldProperties holds,
      ReauthorizeStep reauthorize) {
    this.providers = providers;
    this.tenders = tenders;
    this.props = props;
    this.unknown = unknown;
    this.holds = holds;
    this.reauthorize = reauthorize;
  }

  @Override
  public OrderState handles() {
    return OrderState.PAYMENT_AUTHORIZED;
  }

  @Override
  public StepOutcome execute(Order order, boolean resumed) {
    if (order.captureMode() == CaptureMode.MANUAL && !order.captureRequested()) {
      if (order.reauthorizationRequested() && order.reauthorizedAt() == null) {
        return reauthorize.execute(order, resumed);
      }
      return awaitCapture(order);
    }
    return capture(order, resumed);
  }

  /** What a manual order still waiting when its authorization lapses does: undo, and say why. */
  static StepOutcome.Fail authorizationExpired() {
    return new StepOutcome.Fail(
        "authorization_expired",
        "the authorization expired before the merchant captured the payment",
        OrderState.REVERSING_PAYMENT);
  }

  /** The manual path before the merchant's request: wait for it, at most until the hold lapses. */
  private StepOutcome awaitCapture(Order order) {
    // Orders authorized before expiries were recorded have none; count from the step's start.
    Instant expires =
        order.authorizationExpiresAt() != null
            ? order.authorizationExpiresAt()
            : order.stepStartedAt().plus(holds.authorizationLifetime());
    return new StepOutcome.Await(
        "waiting for the merchant to capture", expires, authorizationExpired());
  }

  private StepOutcome capture(Order order, boolean resumed) {
    Tenders all = Tenders.of(tenders, order);
    Optional<Payment> unexpected =
        all.first(p -> p.state() != PaymentState.AUTHORIZED && p.state() != PaymentState.CAPTURED);
    if (unexpected.isPresent()) {
      throw new IllegalStateException(
          "tender " + unexpected.get().position() + " is " + unexpected.get().state().code());
    }
    Optional<Payment> next = all.first(p -> p.state() == PaymentState.AUTHORIZED);
    if (next.isEmpty()) {
      return new StepOutcome.Advance(OrderState.PAYMENT_CAPTURED, all.money());
    }
    Payment t = next.get();
    if (resumed) {
      CallResult<List<PaymentInfo>> found =
          providers.get(t.provider()).findByReference(t.providerReference());
      if (!(found instanceof CallResult.Success<List<PaymentInfo>> s)) {
        return new StepOutcome.Retry(
            "payment lookup failed: " + found, props.unknownOutcomeDelay());
      }
      Optional<PaymentInfo> ours =
          s.value().stream().filter(p -> p.id().equals(t.providerPaymentId())).findFirst();
      if (ours.isPresent() && ours.get().status() == PaymentStatus.CAPTURED) {
        unknown.resolved("capture", true);
        return captured(all, t, ours.get());
      }
      if (ours.isPresent() && ours.get().status() == PaymentStatus.EXPIRED) {
        unknown.resolved("capture", false);
        return lapsed(all, t);
      }
      if (ours.isEmpty() || ours.get().status() != PaymentStatus.AUTHORIZED) {
        return refused(
            all,
            ours.map(p -> p.status().name().toLowerCase(java.util.Locale.ROOT)).orElse("missing"));
      }
      unknown.resolved("capture", false);
    }
    long amount = t.amount().minorUnits();
    return switch (providers
        .get(t.provider())
        .capture(t.captureKey(), t.providerPaymentId(), amount)) {
      case CallResult.Success<PaymentInfo> s -> captured(all, t, s.value());
      case CallResult.Rejected<PaymentInfo> r ->
          ReauthorizeStep.EXPIRED.equals(r.code()) ? lapsed(all, t) : refused(all, r.code());
      case CallResult.Transient<PaymentInfo> t2 -> new StepOutcome.Retry(t2.reason());
      case CallResult.Unknown<PaymentInfo> u -> {
        unknown.seen("capture");
        yield new StepOutcome.Retry(u.reason(), props.unknownOutcomeDelay());
      }
    };
  }

  private static StepOutcome captured(Tenders all, Payment t, PaymentInfo p) {
    Payment done = t.settled(p.id(), p.captured(), p.refunded(), PaymentState.CAPTURED);
    Tenders after = all.with(done);
    if (after.any(x -> x.state() == PaymentState.AUTHORIZED)) {
      return new StepOutcome.Progress(after.money(), List.of(done));
    }
    return new StepOutcome.Advance(OrderState.PAYMENT_CAPTURED, after.money(), List.of(done));
  }

  /**
   * The issuer dropped tender {@code t}'s hold before we could take the money. Tenders captured
   * before it are given back by the reversal.
   */
  private static StepOutcome lapsed(Tenders all, Payment t) {
    Payment ended = t.settled(null, 0, 0, PaymentState.EXPIRED);
    return new StepOutcome.Fail(
        "authorization_expired",
        "the authorization lapsed at the issuer before the capture",
        OrderState.REVERSING_PAYMENT,
        all.with(ended).money(),
        List.of(ended));
  }

  private static StepOutcome refused(Tenders all, String why) {
    return new StepOutcome.Fail(
        "capture_failed",
        "provider refused the capture: " + why,
        OrderState.REVERSING_PAYMENT,
        all.money(),
        List.of());
  }
}
