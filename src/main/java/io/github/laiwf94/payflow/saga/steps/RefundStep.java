package io.github.laiwf94.payflow.saga.steps;

import io.github.laiwf94.payflow.gateway.CallResult;
import io.github.laiwf94.payflow.gateway.InventoryService;
import io.github.laiwf94.payflow.gateway.PaymentGateway;
import io.github.laiwf94.payflow.gateway.RefundInfo;
import io.github.laiwf94.payflow.gateway.StockLine;
import io.github.laiwf94.payflow.order.Order;
import io.github.laiwf94.payflow.refund.Refund;
import io.github.laiwf94.payflow.refund.RefundAllocation;
import io.github.laiwf94.payflow.refund.RefundAttempt;
import io.github.laiwf94.payflow.refund.RefundOutcome;
import io.github.laiwf94.payflow.refund.RefundState;
import io.github.laiwf94.payflow.saga.SagaProperties;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Makes one refund at the provider, one tender's share at a time. Each share is made at its own
 * tender's provider payment, and asked about first: the provider lists the payment's refunds with
 * the reference each was made under (the refund row's id, unique at a payment since a refund has at
 * most one share per tender), so a share whose answer was lost, or whose worker died mid-call, is
 * found and recorded instead of made again. That holds at a provider that ignores idempotency keys
 * too; the key, one per refund and tender, covers the window between the lookup and the call at one
 * that honours them.
 *
 * <p>A share the provider refuses is recorded as refused and the next share is still made: money
 * promised back goes back wherever it can. Once every share has settled the refund settles:
 * succeeded when all returned their money, failed when none did, partly failed otherwise, naming
 * each tender that refused.
 *
 * <p>A refund of lines then puts those units back into stock, restocking from the order's
 * reservation under the refund row's id, once some money has gone back. Money comes first: a refund
 * the provider refuses whole ends {@code FAILED} and frees its lines to be refunded again, so stock
 * returned before it would be returned twice. A partly failed refund keeps its lines, because the
 * goods did come back, and frees only the money that didn't. The refund settles only once the
 * restock is done too; until then every attempt finds the shares settled and repeats the restock,
 * which the inventory system replays by its id.
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
   * One attempt at {@code refund} of {@code order}: the next pending share if there is one,
   * otherwise the restock and the refund's settlement. {@code resumed} says an earlier attempt may
   * have reached the provider; it only decides how a lookup is counted.
   */
  @Override
  public RefundOutcome execute(Refund refund, Order order, boolean resumed) {
    if (refund.allocations().isEmpty()) {
      return new RefundOutcome.Failed("no_payment", "order has no captured payment to refund");
    }
    for (RefundAllocation share : refund.allocations()) {
      if (share.state() == RefundState.PENDING) {
        return makeShare(refund, share, resumed);
      }
    }
    return settle(refund, order);
  }

  /** The idempotency key of a refund's share on the tender at {@code position}. */
  static String key(Refund refund, int position) {
    return Keys.refund(refund.id()) + (position == 1 ? "" : "-" + position);
  }

  private RefundOutcome makeShare(Refund refund, RefundAllocation share, boolean resumed) {
    if (share.providerPaymentId() == null) {
      return RefundOutcome.ShareSettled.refused(
          share.paymentId(), "no_payment", "tender has no captured payment to refund");
    }
    String reference = refund.id().toString();
    CallResult<List<RefundInfo>> found = payments.findRefunds(share.providerPaymentId());
    if (!(found instanceof CallResult.Success<List<RefundInfo>> s)) {
      return new RefundOutcome.Retry("refund lookup failed: " + found);
    }
    Optional<RefundInfo> made =
        s.value().stream().filter(r -> reference.equals(r.reference())).findFirst();
    if (resumed) {
      unknown.resolved("refund", made.isPresent());
    }
    if (made.isPresent()) {
      return RefundOutcome.ShareSettled.succeeded(share.paymentId(), made.get().id());
    }
    return switch (payments.refund(
        key(refund, share.position()), share.providerPaymentId(), share.amountMinor(), reference)) {
      case CallResult.Success<RefundInfo> ok ->
          RefundOutcome.ShareSettled.succeeded(share.paymentId(), ok.value().id());
      case CallResult.Rejected<RefundInfo> r ->
          RefundOutcome.ShareSettled.refused(share.paymentId(), r.code(), r.message());
      case CallResult.Transient<RefundInfo> t -> new RefundOutcome.Retry(t.reason());
      case CallResult.Unknown<RefundInfo> u -> {
        unknown.seen("refund");
        yield new RefundOutcome.Retry(u.reason(), props.unknownOutcomeDelay());
      }
    };
  }

  /** Every share has settled: restock if anything went back, then say how the refund ended. */
  private RefundOutcome settle(Refund refund, Order order) {
    List<RefundAllocation> shares = refund.allocations();
    List<RefundAllocation> refused =
        shares.stream().filter(a -> a.state() == RefundState.FAILED).toList();
    if (refused.size() == shares.size()) {
      return new RefundOutcome.Failed("provider_refused", refusal(shares, refused));
    }
    String first =
        shares.stream()
            .filter(a -> a.state() == RefundState.SUCCEEDED)
            .findFirst()
            .orElseThrow()
            .providerRefundId();
    RefundOutcome done;
    if (refused.isEmpty()) {
      done = new RefundOutcome.Succeeded(first);
    } else {
      long returned =
          shares.stream()
              .filter(a -> a.state() == RefundState.SUCCEEDED)
              .mapToLong(RefundAllocation::amountMinor)
              .sum();
      done =
          new RefundOutcome.PartiallyFailed(
              first,
              returned,
              "provider_refused",
              refusal(shares, refused)
                  + "; "
                  + returned
                  + " of "
                  + refund.amountMinor()
                  + " returned");
    }
    return restock(refund, order, done);
  }

  /**
   * Why the provider refused: a refund on one tender keeps the message it always had; otherwise
   * each refusing tender is named.
   */
  private static String refusal(List<RefundAllocation> shares, List<RefundAllocation> refused) {
    if (shares.size() == 1) {
      RefundAllocation a = refused.getFirst();
      return "provider refused the refund: " + a.failureCode() + " " + a.failureMessage();
    }
    StringBuilder out = new StringBuilder();
    for (RefundAllocation a : refused) {
      if (!out.isEmpty()) {
        out.append("; ");
      }
      out.append("tender ")
          .append(a.position())
          .append(" refused ")
          .append(a.amountMinor())
          .append(": ")
          .append(a.failureCode())
          .append(' ')
          .append(a.failureMessage());
    }
    return out.toString();
  }

  /** Returns the refund's lines to stock, then reports the refund as {@code done}. */
  private RefundOutcome restock(Refund refund, Order order, RefundOutcome done) {
    if (refund.lines().isEmpty()) {
      return done;
    }
    // The reservation step stores the inventory's id; it reserves under the order id.
    String reservation =
        order.reservationId() != null ? order.reservationId() : order.id().toString();
    List<StockLine> lines =
        refund.lines().stream().map(l -> new StockLine(l.sku(), l.quantity())).toList();
    return switch (inventory.restock(reservation, refund.id().toString(), lines)) {
      case CallResult.Success<String> ok -> done;
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
