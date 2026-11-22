package io.github.laiwf94.payflow.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.laiwf94.payflow.order.Order;
import io.github.laiwf94.payflow.order.Orders;
import io.github.laiwf94.payflow.payment.Payment;
import io.github.laiwf94.payflow.payment.PaymentState;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The order's payment block and method come from its tender row. */
class OrderResponseTest {

  private static final Instant NOW = Instant.parse("2026-11-22T10:00:00Z");

  private static Payment tender(Order o, String providerId, long captured, long refunded) {
    return new Payment(
        UUID.randomUUID(),
        o.id(),
        1,
        "pm_tender",
        Payment.PRIMARY_PROVIDER,
        o.amount(),
        providerId == null ? PaymentState.PENDING : PaymentState.CAPTURED,
        providerId,
        "authorize-" + o.id(),
        "capture-" + o.id(),
        "reauthorize-" + o.id(),
        captured,
        refunded,
        providerId == null ? null : NOW.plusSeconds(3600),
        providerId == null ? null : NOW.plusSeconds(60),
        NOW,
        NOW);
  }

  @Test
  void thePaymentBlockIsTheTenders() {
    Order o = Orders.pending("acme", NOW);
    long amount = o.amount().minorUnits();
    OrderResponse r = OrderResponse.of(o, List.of(tender(o, "pay_9", amount, 300)));
    assertThat(r.paymentMethod()).isEqualTo("pm_tender");
    assertThat(r.payment())
        .isEqualTo(
            new OrderResponse.PaymentSummary(
                "pay_9", amount, 300, NOW.plusSeconds(3600), NOW.plusSeconds(60)));
    assertThat(r.refunds().data()).isEmpty();
  }

  @Test
  void aTenderNotYetAuthorizedShowsNoPayment() {
    Order o = Orders.pending("acme", NOW);
    OrderResponse r = OrderResponse.of(o, List.of(tender(o, null, 0, 0)));
    assertThat(r.payment()).isNull();
    assertThat(r.paymentMethod()).isEqualTo("pm_tender");
  }

  @Test
  void anOrderWithoutItsTenderIsAnError() {
    Order o = Orders.pending("acme", NOW);
    Order other = Orders.pending("acme", NOW);
    assertThatThrownBy(() -> OrderResponse.of(o, List.of()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("has no payment row");
    assertThatThrownBy(() -> OrderResponse.of(o, List.of(tender(other, null, 0, 0))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("belongs to order " + other.id());
  }
}
