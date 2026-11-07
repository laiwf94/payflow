package io.github.laiwf94.payflow.refund;

import io.github.laiwf94.payflow.order.Order;
import io.github.laiwf94.payflow.order.OrderRepository;
import io.github.laiwf94.payflow.order.OrderService;
import io.github.laiwf94.payflow.order.OrderState;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The merchant-facing refund operations. Each creates a {@code PENDING} refund row that the {@link
 * RefundWorker} then makes at the provider; the order moves only when a refund succeeds.
 *
 * <p>Every refund is sized under the order's row lock, the one the database's refund check takes,
 * so two requests can't both be given the last of the capture. The database checks the result again
 * at commit whatever this code does.
 */
@Service
public class RefundService {

  private static final Logger log = LoggerFactory.getLogger(RefundService.class);

  /** The refund asks for more than is left of the capture once other refunds are counted. */
  public static final class ExceedsCaptured extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final long remaining;

    ExceedsCaptured(long requested, long remaining) {
      super("refund of " + requested + " exceeds the " + remaining + " left to refund");
      this.remaining = remaining;
    }

    /** What could still be refunded when the request was refused. */
    public long remaining() {
      return remaining;
    }
  }

  private final OrderRepository orders;
  private final RefundRepository refunds;
  private final RefundWorker worker;
  private final Clock clock;

  public RefundService(
      OrderRepository orders, RefundRepository refunds, RefundWorker worker, Clock clock) {
    this.orders = orders;
    this.refunds = refunds;
    this.worker = worker;
    this.clock = clock;
  }

  /**
   * Refunds everything left of a completed order's capture: what no other refund has returned or is
   * returning. When pending refunds already cover all of it, nothing new is created and the order
   * is returned as it is, so a repeated request is harmless.
   *
   * @throws OrderService.NotFound when the merchant has no such order
   * @throws OrderService.WrongState when the order isn't completed or is already refunded in full
   */
  @Transactional(noRollbackFor = {OrderService.NotFound.class, OrderService.WrongState.class})
  public Order refundRemaining(String merchantId, UUID orderId) {
    Order order = refundable(merchantId, orderId);
    long remaining = order.capturedMinor() - refunds.activeTotal(orderId);
    if (remaining > 0) {
      start(order, remaining, List.of());
    } else if (order.refundedMinor() == order.capturedMinor()) {
      throw notRefundable(OrderState.REFUNDED);
    }
    return order;
  }

  /**
   * Refunds {@code amountMinor} of a completed order, for {@code lines} if it gives goods back
   * (empty otherwise). Returns the new refund, {@code PENDING}.
   *
   * @throws OrderService.NotFound when the merchant has no such order
   * @throws OrderService.WrongState when the order isn't completed or is already refunded in full
   * @throws ExceedsCaptured when other refunds leave less than {@code amountMinor}
   * @throws IllegalArgumentException when {@code amountMinor} isn't positive
   */
  @Transactional(
      noRollbackFor = {
        OrderService.NotFound.class,
        OrderService.WrongState.class,
        ExceedsCaptured.class,
        IllegalArgumentException.class
      })
  public Refund create(String merchantId, UUID orderId, long amountMinor, List<RefundLine> lines) {
    if (amountMinor < 1) {
      throw new IllegalArgumentException("amount must be positive");
    }
    Order order = refundable(merchantId, orderId);
    long remaining = order.capturedMinor() - refunds.activeTotal(orderId);
    if (amountMinor > remaining) {
      throw new ExceedsCaptured(amountMinor, remaining);
    }
    return start(order, amountMinor, lines);
  }

  /** The order's refunds, oldest first. */
  public List<Refund> list(String merchantId, UUID orderId) {
    if (orders.findForMerchant(merchantId, orderId).isEmpty()) {
      throw new OrderService.NotFound();
    }
    return refunds.listForOrder(orderId);
  }

  private Order refundable(String merchantId, UUID orderId) {
    Order order = orders.lockForRefund(merchantId, orderId).orElseThrow(OrderService.NotFound::new);
    if (order.state() != OrderState.COMPLETED && order.state() != OrderState.PARTIALLY_REFUNDED) {
      throw notRefundable(order.state());
    }
    return order;
  }

  private Refund start(Order order, long amountMinor, List<RefundLine> lines) {
    Refund r = Refund.pending(UUID.randomUUID(), order.id(), amountMinor, lines, clock.instant());
    refunds.insert(r);
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            worker.wakeUp();
          }
        });
    log.info("refund {} of {} started", r.id(), amountMinor);
    return r;
  }

  private static OrderService.WrongState notRefundable(OrderState state) {
    return new OrderService.WrongState(
        "order_not_refundable",
        "order is "
            + state.name().toLowerCase(Locale.ROOT)
            + "; only completed orders with money left to refund can be refunded");
  }
}
