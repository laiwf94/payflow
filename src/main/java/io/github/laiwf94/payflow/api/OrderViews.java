package io.github.laiwf94.payflow.api;

import io.github.laiwf94.payflow.order.Order;
import io.github.laiwf94.payflow.order.OrderFilter;
import io.github.laiwf94.payflow.order.OrderPage;
import io.github.laiwf94.payflow.order.OrderPosition;
import io.github.laiwf94.payflow.order.OrderService;
import io.github.laiwf94.payflow.refund.RefundRepository;
import io.github.laiwf94.payflow.refund.RefundSummary;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads orders together with their refunds for the API. Each read is one {@code REPEATABLE READ}
 * snapshot, so an order's refunded amount and state always agree with the refunds shown beside it,
 * even while a refund settles between the two statements.
 */
@Component
public class OrderViews {

  private final OrderService orders;
  private final RefundRepository refunds;

  public OrderViews(OrderService orders, RefundRepository refunds) {
    this.orders = orders;
    this.refunds = refunds;
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
    return OrderResponse.of(o, refunds.summaries(List.of(id)).getOrDefault(id, RefundSummary.NONE));
  }

  /** {@link OrderService#list}, with each order's refunds. */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Page list(String merchantId, OrderFilter filter, OrderPosition after, int limit) {
    OrderPage page = orders.list(merchantId, filter, after, limit);
    Map<UUID, RefundSummary> summaries =
        refunds.summaries(page.orders().stream().map(Order::id).toList());
    return new Page(
        page.orders().stream()
            .map(o -> OrderResponse.of(o, summaries.getOrDefault(o.id(), RefundSummary.NONE)))
            .toList(),
        page.hasMore(),
        page.next());
  }
}
