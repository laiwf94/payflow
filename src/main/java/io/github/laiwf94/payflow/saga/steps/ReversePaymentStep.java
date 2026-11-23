package io.github.laiwf94.payflow.saga.steps;

import io.github.laiwf94.payflow.gateway.CallResult;
import io.github.laiwf94.payflow.gateway.PaymentGateway;
import io.github.laiwf94.payflow.gateway.PaymentInfo;
import io.github.laiwf94.payflow.gateway.PaymentStatus;
import io.github.laiwf94.payflow.gateway.RefundInfo;
import io.github.laiwf94.payflow.order.Order;
import io.github.laiwf94.payflow.order.OrderState;
import io.github.laiwf94.payflow.payment.Payment;
import io.github.laiwf94.payflow.payment.PaymentRepository;
import io.github.laiwf94.payflow.payment.PaymentState;
import io.github.laiwf94.payflow.saga.SagaProperties;
import io.github.laiwf94.payflow.saga.SagaStep;
import io.github.laiwf94.payflow.saga.StepOutcome;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Gives the customer's money back, whatever state the provider has it in, one tender at a time and
 * last tender first: the reverse of the order they were authorized and captured in. Each reversed
 * tender is recorded before the next ({@link StepOutcome.Progress}), so a retry or a resume carries
 * on with the next one still to undo.
 *
 * <p>For each tender it works from the provider's list of payments under the tender's reference,
 * not from the row, so it also undoes an authorization or capture whose answer was lost, and every
 * payment for the reference if more than one exists: holds are voided, captures refunded. The row
 * then records the tender's own payment (the one it recorded, or else the one the provider found)
 * as it ended: refunded, voided, expired, declined, or canceled when the provider never held it. A
 * tender already settled without money (declined, voided, expired, canceled) or fully refunded is
 * left alone.
 */
@Component
class ReversePaymentStep implements SagaStep {

  private final PaymentGateway payments;
  private final PaymentRepository tenders;
  private final SagaProperties props;
  private final UnknownOutcomes unknown;

  ReversePaymentStep(
      PaymentGateway payments,
      PaymentRepository tenders,
      SagaProperties props,
      UnknownOutcomes unknown) {
    this.payments = payments;
    this.tenders = tenders;
    this.props = props;
    this.unknown = unknown;
  }

  @Override
  public OrderState handles() {
    return OrderState.REVERSING_PAYMENT;
  }

  @Override
  public StepOutcome execute(Order order, boolean resumed) {
    Tenders all = Tenders.of(tenders, order);
    Optional<Payment> next = all.last(ReversePaymentStep::mayHoldMoney);
    if (next.isEmpty()) {
      return new StepOutcome.Advance(OrderState.RELEASING_INVENTORY, all.money());
    }
    Payment t = next.get();
    CallResult<List<PaymentInfo>> found = payments.findByReference(t.providerReference());
    if (!(found instanceof CallResult.Success<List<PaymentInfo>> s)) {
      return new StepOutcome.Retry("payment lookup failed: " + found);
    }
    PaymentInfo ours = ours(t, s.value());
    Payment done = null;
    for (PaymentInfo p : s.value()) {
      PaymentInfo now = p;
      long refunded = p.refunded();
      if (p.status() == PaymentStatus.AUTHORIZED) {
        CallResult<PaymentInfo> v = payments.voidAuthorization(Keys.voidPayment(p.id()), p.id());
        if (!(v instanceof CallResult.Success<PaymentInfo> ok)) {
          return notDone(v);
        }
        now = ok.value();
        refunded = now.refunded();
      } else if (p.status() == PaymentStatus.CAPTURED && p.netCaptured() > 0) {
        String key = Keys.reverse(p.id());
        CallResult<RefundInfo> r = payments.refund(key, p.id(), p.netCaptured(), key);
        if (!(r instanceof CallResult.Success<RefundInfo> ok)) {
          return notDone(r);
        }
        refunded += ok.value().amount();
      }
      if (ours != null && p.id().equals(ours.id())) {
        done = t.settled(p.id(), now.captured(), refunded, ended(now.status()));
      }
    }
    if (done == null) {
      done = t.settled(null, 0, 0, PaymentState.CANCELED);
    }
    Tenders after = all.with(done);
    if (after.any(ReversePaymentStep::mayHoldMoney)) {
      return new StepOutcome.Progress(after.money(), List.of(done));
    }
    return new StepOutcome.Advance(OrderState.RELEASING_INVENTORY, after.money(), List.of(done));
  }

  /** A tender that is not known to be settled with all of its money back. */
  private static boolean mayHoldMoney(Payment p) {
    return switch (p.state()) {
      case PENDING, AUTHORIZED, CAPTURED, PARTIALLY_REFUNDED -> true;
      case REFUNDED, VOIDED, EXPIRED, DECLINED, CANCELED -> false;
    };
  }

  /**
   * Which of the provider's payments under the tender's reference is the tender's own: the one it
   * recorded, or, when the answer was lost, the one that took money, else the one still held, else
   * the first. Any others are reversed too but are not the tender's money.
   */
  private static PaymentInfo ours(Payment t, List<PaymentInfo> found) {
    if (found.isEmpty()) {
      return null;
    }
    return found.stream()
        .filter(p -> p.id().equals(t.providerPaymentId()))
        .findFirst()
        .or(() -> found.stream().filter(p -> p.captured() > 0).findFirst())
        .or(() -> found.stream().filter(p -> p.status() == PaymentStatus.AUTHORIZED).findFirst())
        .orElse(found.getFirst());
  }

  /** How a tender whose payment captured nothing ended, from the provider's status. */
  private static PaymentState ended(PaymentStatus s) {
    return switch (s) {
      case DECLINED -> PaymentState.DECLINED;
      case EXPIRED -> PaymentState.EXPIRED;
      case AUTHORIZED, VOIDED, CAPTURED -> PaymentState.VOIDED;
    };
  }

  private StepOutcome notDone(CallResult<?> r) {
    if (r instanceof CallResult.Unknown<?>) {
      unknown.seen("reverse");
      return new StepOutcome.Retry("reversal outcome unknown", props.unknownOutcomeDelay());
    }
    return new StepOutcome.Retry("reversal failed: " + r);
  }
}
