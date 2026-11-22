package io.github.laiwf94.payflow.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.laiwf94.payflow.money.Money;
import java.time.Instant;
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
}
