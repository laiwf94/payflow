package io.github.laiwf94.payflow.sim;

import static io.github.laiwf94.payflow.sim.PaymentProviderSimulator.Status.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.laiwf94.payflow.sim.PaymentProviderSimulator.Payment;
import java.time.Duration;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class PaymentProviderSimulatorTest {

  private final Faults faults = new Faults(new SplittableRandom(1));
  private final SimulatorSettings settings = new SimulatorSettings(Duration.ofSeconds(1));
  private final PaymentProviderSimulator sim = new PaymentProviderSimulator(faults, settings);

  private Payment answered(Simulated<Payment> s) {
    return ((Simulated.Answered<Payment>) s).value();
  }

  @Test
  void authorizeCaptureRefund() {
    Payment p = answered(sim.authorize("k1", "order-1", 2500, "EUR", "pm_card_ok"));
    assertThat(p.status()).isEqualTo(AUTHORIZED);
    Payment c = answered(sim.capture("k2", p.id(), 2500));
    assertThat(c.status()).isEqualTo(CAPTURED);
    assertThat(c.captured()).isEqualTo(2500);
    Payment r = answered(sim.refund("k3", p.id(), 1000));
    assertThat(r.refunded()).isEqualTo(1000);
    assertThatThrownBy(() -> answered(sim.refund("k4", p.id(), 1501)))
        .isInstanceOf(SimulatorException.class)
        .hasMessageContaining("exceeds");
    assertThat(sim.ledgerFor("order-1"))
        .extracting(PaymentProviderSimulator.LedgerEntry::operation)
        .containsExactly("authorize", "capture", "refund");
  }

  @Test
  void declinesAreRecordedWithTheirCode() {
    Payment p = answered(sim.authorize("k", "order-1", 100, "EUR", "pm_card_insufficient_funds"));
    assertThat(p.status()).isEqualTo(DECLINED);
    assertThat(p.declineCode()).isEqualTo("insufficient_funds");
    assertThatThrownBy(() -> sim.capture("k2", p.id(), 100))
        .isInstanceOf(SimulatorException.class)
        .hasMessageContaining("declined");
  }

  @Test
  void captureRules() {
    Payment p = answered(sim.authorize(null, "o", 100, "EUR", "pm_card_ok"));
    assertThatThrownBy(() -> sim.capture(null, p.id(), 101)).hasMessageContaining("authorized");
    assertThatThrownBy(() -> sim.capture(null, p.id(), 0)).hasMessageContaining("authorized");
    answered(sim.capture(null, p.id(), 100));
    assertThatThrownBy(() -> sim.capture(null, p.id(), 100))
        .isInstanceOf(SimulatorException.class)
        .satisfies(e -> assertThat(((SimulatorException) e).code()).isEqualTo("already_captured"));
    assertThatThrownBy(() -> sim.capture(null, "pay_missing", 1))
        .satisfies(e -> assertThat(((SimulatorException) e).status()).isEqualTo(404));
  }

  @Test
  void voidRules() {
    Payment p = answered(sim.authorize(null, "o", 100, "EUR", "pm_card_ok"));
    assertThat(answered(sim.voidAuthorization(null, p.id())).status()).isEqualTo(VOIDED);
    assertThat(answered(sim.voidAuthorization(null, p.id())).status()).isEqualTo(VOIDED);
    Payment q = answered(sim.authorize(null, "o2", 100, "EUR", "pm_card_ok"));
    answered(sim.capture(null, q.id(), 100));
    assertThatThrownBy(() -> sim.voidAuthorization(null, q.id()))
        .satisfies(e -> assertThat(((SimulatorException) e).status()).isEqualTo(409));
  }

  @Test
  void sameKeySameParametersReplaysTheFirstResult() {
    Payment a = answered(sim.authorize("k", "o", 100, "EUR", "pm_card_ok"));
    Payment b = answered(sim.authorize("k", "o", 100, "EUR", "pm_card_ok"));
    assertThat(b).isEqualTo(a);
    assertThat(sim.paymentsFor("o")).hasSize(1);
    assertThatThrownBy(() -> sim.authorize("k", "o", 101, "EUR", "pm_card_ok"))
        .satisfies(e -> assertThat(((SimulatorException) e).code()).isEqualTo("idempotency_error"));
  }

  @Test
  void replayOfAFailedCallReturnsTheSameErrorAndReplayShowsCurrentState() {
    Payment p = answered(sim.authorize(null, "o", 100, "EUR", "pm_card_ok"));
    assertThatThrownBy(() -> sim.capture("cap", p.id(), 500))
        .isInstanceOf(SimulatorException.class);
    assertThatThrownBy(() -> sim.capture("cap", p.id(), 500))
        .hasMessageContaining("authorized amount");
    Payment captured = answered(sim.capture("cap2", p.id(), 100));
    answered(sim.refund("ref", p.id(), 100));
    assertThat(answered(sim.capture("cap2", p.id(), 100)).refunded())
        .as("replay reflects later refund")
        .isEqualTo(100);
    assertThat(captured.refunded()).isZero();
  }

  @Test
  void withoutKeyHandlingEveryRetryCreatesAnotherAuthorization() {
    settings.honorIdempotencyKeys(false);
    answered(sim.authorize("k", "o", 100, "EUR", "pm_card_ok"));
    answered(sim.authorize("k", "o", 100, "EUR", "pm_card_ok"));
    assertThat(sim.paymentsFor("o")).hasSize(2);
  }

  @Test
  void unknownAfterAppliesTheChargeAndUnknownBeforeDoesNot() {
    faults.set(
        "payment.authorize",
        new FaultPlan(0, 0, 0, Duration.ZERO, List.of(Fault.UNKNOWN_BEFORE, Fault.UNKNOWN_AFTER)));
    assertThat(sim.authorize("k", "o", 100, "EUR", "pm_card_ok"))
        .isInstanceOf(Simulated.Unanswered.class);
    assertThat(sim.paymentsFor("o")).isEmpty();
    Simulated<Payment> lost = sim.authorize("k", "o", 100, "EUR", "pm_card_ok");
    assertThat(((Simulated.Unanswered<Payment>) lost).applied()).isNotNull();
    assertThat(sim.paymentsFor("o")).hasSize(1);
  }

  @Test
  void validatesInput() {
    assertThatThrownBy(() -> sim.authorize(null, "", 100, "EUR", "pm_card_ok"))
        .hasMessageContaining("reference");
    assertThatThrownBy(() -> sim.authorize(null, "o", 100, "EUR", null))
        .hasMessageContaining("payment_method");
    assertThatThrownBy(() -> sim.authorize(null, "o", 0, "EUR", "pm_card_ok"))
        .hasMessageContaining("positive");
    assertThatThrownBy(() -> sim.authorize(null, "x".repeat(129), 1, "EUR", "pm_card_ok"))
        .hasMessageContaining("reference");
  }

  @Test
  void resetForgetsEverything() {
    answered(sim.authorize("k", "o", 100, "EUR", "pm_card_ok"));
    sim.reset();
    assertThat(sim.payments()).isEmpty();
    assertThat(sim.ledger()).isEmpty();
    assertThat(answered(sim.authorize("k", "o", 100, "EUR", "pm_card_ok")).id())
        .isEqualTo("pay_00000001");
  }
}
