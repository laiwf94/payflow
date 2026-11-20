package io.github.laiwf94.payflow.saga.steps;

import io.github.laiwf94.payflow.gateway.CallResult;
import io.github.laiwf94.payflow.gateway.PaymentGateway;
import io.github.laiwf94.payflow.gateway.PaymentInfo;
import io.github.laiwf94.payflow.gateway.PaymentStatus;
import io.github.laiwf94.payflow.hold.HoldProperties;
import io.github.laiwf94.payflow.order.CaptureMode;
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
 * Takes the authorized money, on one of two paths.
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

  private final PaymentGateway payments;
  private final SagaProperties props;
  private final UnknownOutcomes unknown;
  private final HoldProperties holds;
  private final ReauthorizeStep reauthorize;

  CapturePaymentStep(
      PaymentGateway payments,
      SagaProperties props,
      UnknownOutcomes unknown,
      HoldProperties holds,
      ReauthorizeStep reauthorize) {
    this.payments = payments;
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
      if (ours.isPresent() && ours.get().status() == PaymentStatus.EXPIRED) {
        unknown.resolved("capture", false);
        return lapsed();
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
      case CallResult.Rejected<PaymentInfo> r ->
          ReauthorizeStep.EXPIRED.equals(r.code()) ? lapsed() : refused(r.code());
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

  /** The issuer dropped the hold before we could take the money. */
  private static StepOutcome lapsed() {
    return new StepOutcome.Fail(
        "authorization_expired",
        "the authorization lapsed at the issuer before the capture",
        OrderState.REVERSING_PAYMENT);
  }

  private static StepOutcome refused(String why) {
    return new StepOutcome.Fail(
        "capture_failed", "provider refused the capture: " + why, OrderState.REVERSING_PAYMENT);
  }
}
