package io.github.laiwf94.payflow.saga.steps;

import io.github.laiwf94.payflow.order.Order;
import io.github.laiwf94.payflow.payment.Payment;
import io.github.laiwf94.payflow.payment.PaymentRepository;
import io.github.laiwf94.payflow.payment.PaymentState;
import io.github.laiwf94.payflow.saga.StepOutcome;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * An order's tenders as a payment step sees them: read once when the step starts, then updated in
 * memory as the step learns what the provider did, so the order's own columns can be written as
 * their sum in the same write as the tender that changed.
 *
 * <p>The order's columns are the saga's summary of its tenders: {@code payment_id} is the first
 * tender's provider id, which refunds are made against, {@code captured_minor} and {@code
 * refunded_minor} are the sums, and {@code authorization_expires_at} is the earliest hold to lapse,
 * which is when a manual-capture order has to be captured by.
 */
final class Tenders {

  private final List<Payment> all;

  private Tenders(List<Payment> all) {
    this.all = List.copyOf(all);
  }

  /**
   * The tenders of {@code order} by position.
   *
   * @throws IllegalStateException when it has none; every order is stored with at least one, so the
   *     step can only retry and be noticed
   */
  static Tenders of(PaymentRepository repo, Order order) {
    List<Payment> rows = repo.forOrder(order.id());
    if (rows.isEmpty()) {
      throw new IllegalStateException("order " + order.id() + " has no tenders");
    }
    return new Tenders(rows);
  }

  static Tenders of(List<Payment> rows) {
    return new Tenders(rows);
  }

  List<Payment> all() {
    return all;
  }

  /** The first tender by position that {@code which} holds for. */
  Optional<Payment> first(Predicate<Payment> which) {
    return all.stream().filter(which).findFirst();
  }

  /** The last tender by position that {@code which} holds for: compensation runs backwards. */
  Optional<Payment> last(Predicate<Payment> which) {
    return all.reversed().stream().filter(which).findFirst();
  }

  boolean any(Predicate<Payment> which) {
    return all.stream().anyMatch(which);
  }

  /** These tenders with {@code changed} in place of the row it updates. */
  Tenders with(Payment changed) {
    List<Payment> next = new ArrayList<>(all.size());
    boolean found = false;
    for (Payment p : all) {
      if (p.id().equals(changed.id())) {
        next.add(changed);
        found = true;
      } else {
        next.add(p);
      }
    }
    if (!found) {
      throw new IllegalArgumentException("payment " + changed.id() + " is not one of these");
    }
    return new Tenders(next);
  }

  /** The earliest time a held authorization lapses, or null when none records one. */
  Instant earliestExpiry() {
    return all.stream()
        .filter(p -> p.state() == PaymentState.AUTHORIZED)
        .map(Payment::authorizationExpiresAt)
        .filter(t -> t != null)
        .min(Instant::compareTo)
        .orElse(null);
  }

  long captured() {
    return all.stream().mapToLong(Payment::capturedMinor).sum();
  }

  long refunded() {
    return all.stream().mapToLong(Payment::refundedMinor).sum();
  }

  /** The order's summary after an authorization: the first tender's id and the earliest expiry. */
  StepOutcome.Fields authorization() {
    return new StepOutcome.Fields(
        null, all.getFirst().providerPaymentId(), null, null, null, null, earliestExpiry(), null);
  }

  /** The order's summary of the money: what the tenders captured and gave back, in total. */
  StepOutcome.Fields money() {
    return StepOutcome.Fields.money(captured(), refunded());
  }
}
