package io.github.laiwf94.payflow.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.laiwf94.payflow.money.Money;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentTest {

  private static Payment payment(int position, long captured, long refunded) {
    Instant now = Instant.parse("2026-11-22T00:00:00Z");
    return new Payment(
        UUID.randomUUID(),
        UUID.randomUUID(),
        position,
        "pm_card_ok",
        Payment.PRIMARY_PROVIDER,
        Payment.DEFAULT_RULE,
        Money.of(1000, "EUR"),
        PaymentState.CAPTURED,
        "pay_1",
        "a",
        "c",
        "r",
        captured,
        refunded,
        null,
        null,
        now,
        now);
  }

  @Test
  void moneyMustFitTheTender() {
    assertThat(payment(1, 1000, 400).netCapturedMinor()).isEqualTo(600);
    assertThatThrownBy(() -> payment(0, 0, 0)).hasMessageContaining("position");
    assertThatThrownBy(() -> payment(1, 1001, 0)).hasMessageContaining("amount of 1000");
    assertThatThrownBy(() -> payment(1, -1, 0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> payment(1, 500, 501)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> payment(1, 500, -1)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void statesRoundTripTheirStoredCodes() {
    for (PaymentState s : PaymentState.values()) {
      assertThat(PaymentState.fromCode(s.code())).isEqualTo(s);
    }
    assertThat(PaymentState.PARTIALLY_REFUNDED.code()).isEqualTo("partially_refunded");
    assertThat(PaymentState.PENDING.isSettled()).isFalse();
    assertThat(PaymentState.AUTHORIZED.isSettled()).isFalse();
    assertThat(PaymentState.VOIDED.isSettled()).isTrue();
    assertThatThrownBy(() -> PaymentState.fromCode("PENDING"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aNewTenderIsPendingWithKeysAndAReferenceOfItsOwn() {
    UUID order = UUID.randomUUID();
    Instant now = Instant.parse("2026-11-23T00:00:00Z");
    Payment first = Payment.create(UUID.randomUUID(), order, 1, "pm_card_ok", eur(1000), now);
    Payment second = Payment.create(UUID.randomUUID(), order, 2, "pm_gift", eur(500), now);
    assertThat(first.state()).isEqualTo(PaymentState.PENDING);
    assertThat(first.provider()).isEqualTo(Payment.PRIMARY_PROVIDER);
    assertThat(List.of(first.authorizeKey(), first.captureKey(), first.reauthorizeKey()))
        .containsExactly("authorize-" + order, "capture-" + order, "reauthorize-" + order);
    assertThat(List.of(second.authorizeKey(), second.captureKey(), second.reauthorizeKey()))
        .containsExactly(
            "authorize-" + order + "-2", "capture-" + order + "-2", "reauthorize-" + order + "-2");
    assertThat(first.providerReference()).isEqualTo(order.toString());
    assertThat(second.providerReference()).isEqualTo(order + "-2");
    assertThat(first.holdsMoney()).isFalse();
  }

  @Test
  void aTenderMovesWithWhatTheProviderReports() {
    Instant now = Instant.parse("2026-11-23T00:00:00Z");
    Instant expires = now.plusSeconds(3600);
    Payment p = Payment.create(UUID.randomUUID(), UUID.randomUUID(), 1, "m", eur(1000), now);
    Payment held = p.authorized("pay_1", expires);
    assertThat(held.state()).isEqualTo(PaymentState.AUTHORIZED);
    assertThat(held.providerPaymentId()).isEqualTo("pay_1");
    assertThat(held.authorizationExpiresAt()).isEqualTo(expires);
    assertThat(held.holdsMoney()).isTrue();

    Payment extended = held.reauthorized(expires.plusSeconds(60), now);
    assertThat(extended.state()).isEqualTo(PaymentState.AUTHORIZED);
    assertThat(extended.authorizationExpiresAt()).isEqualTo(expires.plusSeconds(60));
    assertThat(extended.reauthorizedAt()).isEqualTo(now);

    Payment captured = extended.settled(null, 1000, 0, PaymentState.VOIDED);
    assertThat(captured.state()).isEqualTo(PaymentState.CAPTURED);
    assertThat(captured.providerPaymentId()).as("kept").isEqualTo("pay_1");
    assertThat(captured.holdsMoney()).isTrue();
    assertThat(captured.settled(null, 1000, 400, PaymentState.VOIDED).state())
        .isEqualTo(PaymentState.PARTIALLY_REFUNDED);
    Payment back = captured.settled(null, 1000, 1000, PaymentState.VOIDED);
    assertThat(back.state()).isEqualTo(PaymentState.REFUNDED);
    assertThat(back.holdsMoney()).isFalse();
    assertThat(held.settled("pay_2", 0, 0, PaymentState.EXPIRED))
        .extracting(Payment::state, Payment::providerPaymentId)
        .containsExactly(PaymentState.EXPIRED, "pay_2");
    assertThatThrownBy(() -> held.settled(null, 1001, 0, PaymentState.VOIDED))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static Money eur(long minor) {
    return Money.of(minor, "EUR");
  }
}
