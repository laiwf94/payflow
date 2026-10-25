package io.github.laiwf94.payflow.order;

import io.github.laiwf94.payflow.money.InvalidMoneyException;
import io.github.laiwf94.payflow.money.Money;
import io.github.laiwf94.payflow.saga.SagaWorker;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** The merchant-facing operations on orders. */
@Service
public class OrderService {

  private static final Set<String> COUNTRIES = Set.of(Locale.getISOCountries());

  /** Another merchant's order is reported exactly like a missing one. */
  public static final class NotFound extends RuntimeException {
    private static final long serialVersionUID = 1L;

    NotFound() {
      super("order not found");
    }
  }

  /** The order exists but is in a state where the request can't apply. */
  public static final class WrongState extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String code;

    WrongState(String code, String message) {
      super(message);
      this.code = code;
    }

    public String code() {
      return code;
    }
  }

  private final OrderRepository repo;
  private final OrderTransitions transitions;
  private final SagaWorker worker;
  private final Clock clock;

  public OrderService(
      OrderRepository repo, OrderTransitions transitions, SagaWorker worker, Clock clock) {
    this.repo = repo;
    this.transitions = transitions;
    this.worker = worker;
    this.clock = clock;
  }

  /**
   * Creates an order in {@code PENDING}. The saga starts once the transaction commits.
   *
   * @throws InvalidMoneyException when the total overflows the per-charge cap
   * @throws IllegalArgumentException when the country isn't an ISO 3166 code
   */
  // A refused request writes nothing, and the caller (the idempotency filter) still has to commit
  // the stored error response in the same transaction, so refusals must not mark it rollback-only.
  @Transactional(noRollbackFor = IllegalArgumentException.class)
  public Order create(
      String merchantId,
      List<LineItem> items,
      String currency,
      ShippingAddress address,
      String paymentMethod,
      String correlationId) {
    if (!COUNTRIES.contains(address.country())) {
      throw new IllegalArgumentException("shipping_address.country: unknown country code");
    }
    Money total = null;
    for (LineItem i : items) {
      Money line = Money.of(i.unitAmount(), currency).times(i.quantity());
      total = total == null ? line : total.plus(line);
    }
    Order order =
        Order.create(
            UUID.randomUUID(),
            merchantId,
            total,
            items,
            address,
            paymentMethod,
            correlationId,
            clock.instant());
    transitions.create(order);
    wakeWorkerAfterCommit();
    return order;
  }

  public Order get(String merchantId, UUID id) {
    return repo.findForMerchant(merchantId, id).orElseThrow(NotFound::new);
  }

  @Transactional(noRollbackFor = {NotFound.class, WrongState.class})
  public Order cancel(String merchantId, UUID id) {
    Optional<Order> o = transitions.requestCancel(merchantId, id, clock.instant());
    if (o.isPresent()) {
      wakeWorkerAfterCommit();
      return o.get();
    }
    Order current = get(merchantId, id);
    if (current.cancelRequested() && !current.state().isTerminal()) {
      return current;
    }
    throw new WrongState(
        "order_not_cancellable",
        "order is "
            + current.state().name().toLowerCase(Locale.ROOT)
            + "; only orders not yet"
            + " captured can be cancelled, use refund after completion");
  }

  @Transactional(noRollbackFor = {NotFound.class, WrongState.class})
  public Order refund(String merchantId, UUID id) {
    Optional<Order> o = transitions.requestRefund(merchantId, id, clock.instant());
    if (o.isPresent()) {
      wakeWorkerAfterCommit();
      return o.get();
    }
    Order current = get(merchantId, id);
    if (current.state() == OrderState.REFUNDING) {
      return current;
    }
    throw new WrongState(
        "order_not_refundable",
        "order is "
            + current.state().name().toLowerCase(Locale.ROOT)
            + "; only completed orders"
            + " can be refunded");
  }

  private void wakeWorkerAfterCommit() {
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            worker.wakeUp();
          }
        });
  }
}
