package io.github.laiwf94.payflow.refund;

import io.github.laiwf94.payflow.order.LineItem;
import io.github.laiwf94.payflow.order.Order;
import io.github.laiwf94.payflow.order.OrderRepository;
import io.github.laiwf94.payflow.order.OrderService;
import io.github.laiwf94.payflow.order.OrderState;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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

  /** The refund gives back more of a sku than the order bought, once other refunds are counted. */
  public static final class ExceedsPurchased extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String sku;
    private final long remaining;

    ExceedsPurchased(String sku, long requested, long remaining) {
      super(
          "refund of "
              + requested
              + " of "
              + sku
              + " exceeds the "
              + remaining
              + " left to refund");
      this.sku = sku;
      this.remaining = remaining;
    }

    /** The sku asked for beyond what is left. */
    public String sku() {
      return sku;
    }

    /** How many of {@link #sku()} could still be refunded when the request was refused. */
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

  /**
   * Refunds goods: {@code lines} of a completed order, each at the price the order paid for it.
   * Units of a sku come back from the order's lines in their order, after the units other refunds
   * have already given back, so a sku sold on two lines at two prices is priced the same way
   * however its units are split across refunds. Returns the new refund, {@code PENDING}, whose
   * amount is the lines' share.
   *
   * @throws OrderService.NotFound when the merchant has no such order
   * @throws OrderService.WrongState when the order isn't completed or is already refunded in full
   * @throws IllegalArgumentException when {@code lines} is empty, names a sku twice or names a sku
   *     the order doesn't have
   * @throws ExceedsPurchased when other refunds leave fewer of a sku than asked for
   * @throws ExceedsCaptured when other refunds leave less money than the lines' share
   */
  @Transactional(
      noRollbackFor = {
        OrderService.NotFound.class,
        OrderService.WrongState.class,
        ExceedsCaptured.class,
        ExceedsPurchased.class,
        IllegalArgumentException.class
      })
  public Refund refundLines(String merchantId, UUID orderId, List<RefundLine> lines) {
    if (lines.isEmpty()) {
      throw new IllegalArgumentException("lines: must name at least one line");
    }
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < lines.size(); i++) {
      RefundLine l = lines.get(i);
      if (l.quantity() < 1) {
        throw new IllegalArgumentException("lines[" + i + "].quantity: must be at least 1");
      }
      if (!seen.add(l.sku())) {
        throw new IllegalArgumentException(
            "lines[" + i + "].sku: " + l.sku() + " is named twice; give each sku once");
      }
    }
    Order order = refundable(merchantId, orderId);
    for (int i = 0; i < lines.size(); i++) {
      String sku = lines.get(i).sku();
      if (order.items().stream().noneMatch(item -> item.sku().equals(sku))) {
        throw new IllegalArgumentException(
            "lines[" + i + "].sku: " + sku + " is not on this order");
      }
    }
    Map<String, Long> given = refunds.activeLineQuantities(orderId);
    long amount = 0;
    for (RefundLine l : lines) {
      amount += share(order.items(), l, given.getOrDefault(l.sku(), 0L));
    }
    long remaining = order.capturedMinor() - refunds.activeTotal(orderId);
    if (amount > remaining) {
      throw new ExceedsCaptured(amount, remaining);
    }
    return start(order, amount, lines);
  }

  /**
   * What {@code line} returns: its units priced from the order's lines for that sku, first line
   * first, skipping the {@code alreadyGiven} units earlier refunds took.
   */
  static long share(List<LineItem> items, RefundLine line, long alreadyGiven) {
    long skip = alreadyGiven;
    long wanted = line.quantity();
    long bought = 0;
    long amount = 0;
    for (LineItem item : items) {
      if (!item.sku().equals(line.sku())) {
        continue;
      }
      bought += item.quantity();
      long left = item.quantity();
      long skipped = Math.min(skip, left);
      skip -= skipped;
      left -= skipped;
      long take = Math.min(wanted, left);
      wanted -= take;
      amount += take * item.unitAmount();
    }
    if (wanted > 0) {
      throw new ExceedsPurchased(line.sku(), line.quantity(), Math.max(0, bought - alreadyGiven));
    }
    return amount;
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
