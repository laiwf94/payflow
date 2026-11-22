package io.github.laiwf94.payflow.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.laiwf94.payflow.order.Order;
import io.github.laiwf94.payflow.order.OrderRepository;
import io.github.laiwf94.payflow.order.Orders;
import io.github.laiwf94.payflow.support.Db;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The payments table and the trigger that keeps each order's single tender equal to the saga's copy
 * on the order row, driven through raw SQL as the saga's statements would write the order.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class PaymentSchemaTest {

  @Autowired JdbcClient jdbc;
  @Autowired OrderRepository orders;
  @Autowired PaymentRepository payments;

  @BeforeEach
  void clean() {
    Db.truncateAll(jdbc);
  }

  private Order insert() {
    Order o = Orders.pending("acme", Instant.now().truncatedTo(ChronoUnit.MICROS));
    assertThat(orders.insert(o)).isTrue();
    return o;
  }

  private void set(UUID id, String assignments) {
    jdbc.sql("UPDATE orders SET " + assignments + " WHERE id = :id").param("id", id).update();
  }

  private Payment tender(UUID id) {
    List<Payment> rows = payments.forOrder(id);
    assertThat(rows).hasSize(1);
    return rows.getFirst();
  }

  @Test
  void aNewOrderHasOneTenderForItsWholeAmountWithTheKeysTheStepsSend() {
    Order o = insert();
    Payment p = tender(o.id());
    assertThat(p.orderId()).isEqualTo(o.id());
    assertThat(p.position()).isOne();
    assertThat(p.method()).isEqualTo(o.paymentMethod());
    assertThat(p.provider()).isEqualTo(Payment.PRIMARY_PROVIDER);
    assertThat(p.amount()).isEqualTo(o.amount());
    assertThat(p.state()).isEqualTo(PaymentState.PENDING);
    assertThat(p.providerPaymentId()).isNull();
    assertThat(p.authorizeKey()).isEqualTo("authorize-" + o.id());
    assertThat(p.captureKey()).isEqualTo("capture-" + o.id());
    assertThat(p.reauthorizeKey()).isEqualTo("reauthorize-" + o.id());
    assertThat(p.capturedMinor()).isZero();
    assertThat(p.refundedMinor()).isZero();
    assertThat(p.netCapturedMinor()).isZero();
    assertThat(p.createdAt()).isEqualTo(o.createdAt());
    assertThat(p.updatedAt()).isEqualTo(o.updatedAt());
  }

  @Test
  void theTenderFollowsTheOrderFromAuthorizationThroughCaptureAndRefunds() {
    Order o = insert();
    UUID id = o.id();
    long amount = o.amount().minorUnits();
    Instant expires = Instant.parse("2026-12-01T10:00:00.123456Z");
    Instant extended = Instant.parse("2026-11-28T09:30:00Z");

    set(id, "state = 'INVENTORY_RESERVED'");
    assertThat(tender(id).state()).isEqualTo(PaymentState.PENDING);

    set(
        id,
        "state = 'PAYMENT_AUTHORIZED', payment_id = 'pay_1',"
            + " authorization_expires_at = '"
            + expires
            + "'");
    Payment authorized = tender(id);
    assertThat(authorized.state()).isEqualTo(PaymentState.AUTHORIZED);
    assertThat(authorized.providerPaymentId()).isEqualTo("pay_1");
    assertThat(authorized.authorizationExpiresAt()).isEqualTo(expires);

    set(id, "capture_mode = 'manual', reauthorization_requested = true");
    set(id, "reauthorized_at = '" + extended + "'");
    assertThat(tender(id).reauthorizedAt()).isEqualTo(extended);

    set(id, "state = 'PAYMENT_CAPTURED', captured_minor = amount_minor");
    assertThat(tender(id).state()).isEqualTo(PaymentState.CAPTURED);
    assertThat(tender(id).capturedMinor()).isEqualTo(amount);

    set(id, "state = 'COMPLETED', next_attempt_at = NULL");
    set(id, "state = 'PARTIALLY_REFUNDED', refunded_minor = 1000");
    Payment part = tender(id);
    assertThat(part.state()).isEqualTo(PaymentState.PARTIALLY_REFUNDED);
    assertThat(part.refundedMinor()).isEqualTo(1000);
    assertThat(part.netCapturedMinor()).isEqualTo(amount - 1000);

    set(id, "state = 'REFUNDED', refunded_minor = captured_minor, updated_at = now()");
    Payment refunded = tender(id);
    assertThat(refunded.state()).isEqualTo(PaymentState.REFUNDED);
    assertThat(refunded.netCapturedMinor()).isZero();
    assertThat(refunded.updatedAt()).isEqualTo(orders.findById(id).orElseThrow().updatedAt());
    assertThat(refunded.id()).as("the same row all along").isEqualTo(authorized.id());
  }

  @Test
  void anOrderThatFailsSaysWhatBecameOfItsTender() {
    record Case(String setup, String failureCode, PaymentState expected) {}
    List<Case> cases =
        List.of(
            new Case("state = 'RELEASING_INVENTORY'", "payment_declined", PaymentState.DECLINED),
            new Case(
                "state = 'RELEASING_INVENTORY'", "authorization_expired", PaymentState.EXPIRED),
            new Case("state = 'RELEASING_INVENTORY'", "out_of_stock", PaymentState.CANCELED),
            new Case(
                "state = 'PAYMENT_AUTHORIZED', payment_id = 'pay_v'",
                "provider_timeout",
                PaymentState.VOIDED),
            new Case(
                "state = 'PAYMENT_AUTHORIZED', payment_id = 'pay_x'",
                "authorization_expired",
                PaymentState.EXPIRED));
    for (Case c : cases) {
      UUID id = insert().id();
      set(id, "state = 'INVENTORY_RESERVED'");
      set(id, c.setup());
      if (c.setup().contains("PAYMENT_AUTHORIZED")) {
        assertThat(tender(id).state()).isEqualTo(PaymentState.AUTHORIZED);
        set(id, "state = 'REVERSING_PAYMENT'");
        assertThat(tender(id).state())
            .as("still held while it is reversed")
            .isEqualTo(PaymentState.AUTHORIZED);
        set(id, "state = 'RELEASING_INVENTORY'");
      }
      set(id, "failure_code = '" + c.failureCode() + "', failure_message = 'x'");
      assertThat(tender(id).state())
          .as("%s, %s", c.setup(), c.failureCode())
          .isEqualTo(c.expected());
      set(id, "state = 'FAILED', next_attempt_at = NULL");
      assertThat(tender(id).state()).as("failed after %s", c.setup()).isEqualTo(c.expected());
      assertThat(tender(id).state().isSettled()).isTrue();
    }
  }

  @Test
  void aReversalThatRefundedALostCaptureIsRefundedWithoutAProviderId() {
    UUID id = insert().id();
    set(id, "state = 'INVENTORY_RESERVED'");
    set(id, "state = 'REVERSING_PAYMENT'");
    set(id, "state = 'RELEASING_INVENTORY', captured_minor = 2500, refunded_minor = 2500");
    set(id, "state = 'CANCELLED', next_attempt_at = NULL");
    Payment p = tender(id);
    assertThat(p.state()).isEqualTo(PaymentState.REFUNDED);
    assertThat(p.providerPaymentId()).isNull();
  }

  @Test
  void writesThatLeaveTheSagasCopyAloneDoNotTouchTheTender() {
    UUID id = insert().id();
    String before = rowVersion(id);
    set(id, "lease_token = gen_random_uuid(), lease_until = now() + interval '30 seconds'");
    set(id, "attempt = 3, next_attempt_at = now(), version = version + 1, updated_at = now()");
    set(
        id,
        "lease_token = NULL, lease_until = NULL, cancel_requested = true,"
            + " cancel_reason = 'requested'");
    assertThat(rowVersion(id)).as("no new row version of the tender").isEqualTo(before);
    set(id, "state = 'INVENTORY_RESERVED'");
    assertThat(rowVersion(id)).isNotEqualTo(before);
  }

  private String rowVersion(UUID order) {
    return jdbc.sql("SELECT xmin::text FROM payments WHERE order_id = :id")
        .param("id", order)
        .query(String.class)
        .single();
  }

  @Test
  void theDatabaseRefusesTendersThatBreakTheMoneyRules() {
    UUID id = insert().id();
    Map<String, String> refused =
        Map.of(
            "captured_minor = amount_minor + 1", "payments_money_bounds",
            "state = 'captured', captured_minor = 100, refunded_minor = 101",
                "payments_money_bounds",
            "state = 'captured'", "payments_state_money",
            "state = 'refunded', captured_minor = 100, refunded_minor = 50", "payments_state_money",
            "state = 'pending', captured_minor = 100", "payments_state_money",
            "state = 'settled'", "payments_known_state",
            "position = 0", "payments_position_range",
            "provider = 'Primary'", "payments_provider_format",
            "capture_key = authorize_key", "payments_keys_distinct",
            "authorization_expires_at = 'infinity'", "payments_times_finite");
    refused.forEach(
        (assignments, constraint) ->
            assertThatThrownBy(
                    () ->
                        jdbc.sql("UPDATE payments SET " + assignments + " WHERE order_id = :id")
                            .param("id", id)
                            .update())
                .as(assignments)
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(constraint));
    assertThatThrownBy(() -> insertTender(id, 1, "again"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("payments_order_position");
    assertThatThrownBy(() -> insertTender(UUID.randomUUID(), 1, "orphan"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("payments_order_id_fkey");
    assertThat(tender(id).state()).isEqualTo(PaymentState.PENDING);
  }

  private void insertTender(UUID order, int position, String keySuffix) {
    jdbc.sql(
            """
            INSERT INTO payments (id, order_id, position, method, provider, amount_minor,
                currency, state, authorize_key, capture_key, reauthorize_key, created_at,
                updated_at)
            VALUES (gen_random_uuid(), :order, :position, 'pm_gift_card', 'primary', 500, 'EUR',
                'pending', 'a-' || :suffix, 'c-' || :suffix, 'r-' || :suffix, now(), now())
            """)
        .param("order", order)
        .param("position", position)
        .param("suffix", keySuffix)
        .update();
  }

  @Test
  void tendersAreReadByOrderInPositionOrder() {
    UUID split = insert().id();
    UUID single = insert().id();
    insertTender(split, 3, "third");
    insertTender(split, 2, "second");

    assertThat(payments.forOrder(split)).extracting(Payment::position).containsExactly(1, 2, 3);
    assertThat(payments.forOrder(split).get(1).method()).isEqualTo("pm_gift_card");
    assertThat(payments.forOrder(UUID.randomUUID())).isEmpty();

    UUID missing = UUID.randomUUID();
    Map<UUID, List<Payment>> byOrder = payments.forOrders(List.of(split, single, missing));
    assertThat(byOrder).containsOnlyKeys(split, single);
    assertThat(byOrder.get(split)).extracting(Payment::position).containsExactly(1, 2, 3);
    assertThat(byOrder.get(single)).singleElement().extracting(Payment::orderId).isEqualTo(single);
    assertThat(payments.forOrders(List.of())).isEmpty();
  }
}
