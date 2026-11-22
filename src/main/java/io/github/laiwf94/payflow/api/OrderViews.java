package io.github.laiwf94.payflow.api;

import io.github.laiwf94.payflow.order.Order;
import io.github.laiwf94.payflow.order.OrderFilter;
import io.github.laiwf94.payflow.order.OrderPage;
import io.github.laiwf94.payflow.order.OrderPosition;
import io.github.laiwf94.payflow.order.OrderService;
import io.github.laiwf94.payflow.payment.Payment;
import io.github.laiwf94.payflow.payment.PaymentRepository;
import io.github.laiwf94.payflow.refund.RefundRepository;
import io.github.laiwf94.payflow.refund.RefundSummary;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads orders together with their tenders and refunds for the API. Each read is one {@code
 * REPEATABLE READ} snapshot, so an order's refunded amount and state always agree with the payment
 * and refunds shown beside it, even while a step or a refund settles between the statements.
 *
 * <p>{@link #of(Order)} and {@link #of(Order, RefundSummary)} complete an order a write just
 * returned. They join the caller's transaction, which holds that order's row lock from the write
 * until it commits, so no step can change the tender in between.
 */
@Component
public class OrderViews {

  private final OrderService orders;
  private final RefundRepository refunds;
  private final PaymentRepository payments;

  public OrderViews(OrderService orders, RefundRepository refunds, PaymentRepository payments) {
    this.orders = orders;
    this.refunds = refunds;
    this.payments = payments;
  }

  /** A page of orders with their refunds. */
  public record Page(List<OrderResponse> orders, boolean hasMore, OrderPosition next) {}

  /**
   * The merchant's order with its refunds.
   *
   * @throws OrderService.NotFound when the merchant has no such order
   */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public OrderResponse get(String merchantId, UUID id) {
    Order o = orders.get(merchantId, id);
    return OrderResponse.of(
        o,
        payments.forOrder(id),
        refunds.summaries(List.of(id)).getOrDefault(id, RefundSummary.NONE));
  }

  /** An order a write just returned, which has no refunds to show. */
  @Transactional(readOnly = true)
  public OrderResponse of(Order o) {
    return OrderResponse.of(o, payments.forOrder(o.id()));
  }

  /** An order a write just returned, with the refunds that write read beside it. */
  @Transactional(readOnly = true)
  public OrderResponse of(Order o, RefundSummary summary) {
    return OrderResponse.of(o, payments.forOrder(o.id()), summary);
  }

  /** {@link OrderService#list}, with each order's refunds. */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Page list(String merchantId, OrderFilter filter, OrderPosition after, int limit) {
    OrderPage page = orders.list(merchantId, filter, after, limit);
    List<UUID> ids = page.orders().stream().map(Order::id).toList();
    Map<UUID, RefundSummary> summaries = refunds.summaries(ids);
    Map<UUID, List<Payment>> tenders = payments.forOrders(ids);
    return new Page(
        page.orders().stream()
            .map(
                o ->
                    OrderResponse.of(
                        o,
                        tenders.getOrDefault(o.id(), List.of()),
                        summaries.getOrDefault(o.id(), RefundSummary.NONE)))
            .toList(),
        page.hasMore(),
        page.next());
  }
}
