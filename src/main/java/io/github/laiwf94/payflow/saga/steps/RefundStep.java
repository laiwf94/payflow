package io.github.laiwf94.payflow.saga.steps;

import io.github.laiwf94.payflow.gateway.CallResult;
import io.github.laiwf94.payflow.gateway.InventoryService;
import io.github.laiwf94.payflow.gateway.PaymentGateway;
import io.github.laiwf94.payflow.gateway.RefundInfo;
import io.github.laiwf94.payflow.gateway.StockLine;
import io.github.laiwf94.payflow.order.Order;
import io.github.laiwf94.payflow.refund.Refund;
import io.github.laiwf94.payflow.refund.RefundAttempt;
import io.github.laiwf94.payflow.refund.RefundOutcome;
import io.github.laiwf94.payflow.saga.SagaProperties;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Makes one refund at the provider. It asks first: the provider lists the payment's refunds with
 * the reference each was made under (the refund row's id), so a refund whose answer was lost, or
 * whose worker died mid-call, is found and recorded instead of made again. That holds at a provider
 * that ignores idempotency keys too; the key, one per refund row, covers the window between the
 * lookup and the call at one that honours them.
 *
 * <p>A refund of lines then puts those units back into stock, restocking from the order's
 * reservation under the refund row's id. Money comes first: a refund the provider refuses ends
 * {@code FAILED} and frees its lines to be refunded again, so stock returned before it would be
 * returned twice. The refund only succeeds once both are done; until then every attempt finds the
 * provider's refund instead of making another and repeats the restock, which the inventory system
 * replays by its id.
 */
@Component
public class RefundStep implements RefundAttempt {

  private static final Logger log = LoggerFactory.getLogger(RefundStep.class);

  private final PaymentGateway payments;
  private final InventoryService inventory;
  private final SagaProperties props;
  private final UnknownOutcomes unknown;

  RefundStep(
      PaymentGateway payments,
      InventoryService inventory,
      SagaProperties props,
      UnknownOutcomes unknown) {
    this.payments = payments;
    this.inventory = inventory;
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
      return restock(refund, order, made.get().id());
    }
    return switch (payments.refund(
        Keys.refund(refund.id()), order.paymentId(), refund.amountMinor(), reference)) {
      case CallResult.Success<RefundInfo> ok -> restock(refund, order, ok.value().id());
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

  /** Returns the refund's lines to stock, then reports the refund made as {@code refundId}. */
  private RefundOutcome restock(Refund refund, Order order, String refundId) {
    if (refund.lines().isEmpty()) {
      return new RefundOutcome.Succeeded(refundId);
    }
    // The reservation step stores the inventory's id; it reserves under the order id.
    String reservation =
        order.reservationId() != null ? order.reservationId() : order.id().toString();
    List<StockLine> lines =
        refund.lines().stream().map(l -> new StockLine(l.sku(), l.quantity())).toList();
    return switch (inventory.restock(reservation, refund.id().toString(), lines)) {
      case CallResult.Success<String> ok -> new RefundOutcome.Succeeded(refundId);
      case CallResult.Rejected<String> r -> {
        // The database bounds a refund's lines by what the order bought, so this is the inventory
        // system disagreeing about the reservation. The money is already back; keep trying and
        // make noise, as the release step does, rather than drop the stock or the refund.
        log.error(
            "inventory refused to restock refund {}: {} {}", refund.id(), r.code(), r.message());
        yield new RefundOutcome.Retry("inventory refused the restock: " + r.code());
      }
      case CallResult.Transient<String> t -> new RefundOutcome.Retry("restock: " + t.reason());
      case CallResult.Unknown<String> u -> new RefundOutcome.Retry("restock: " + u.reason());
    };
  }
}
