package io.github.laiwf94.payflow.sim;

import static io.github.laiwf94.payflow.sim.PaymentProviderSimulator.Status.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.laiwf94.payflow.sim.PaymentProviderSimulator.Payment;
import io.github.laiwf94.payflow.sim.PaymentProviderSimulator.Refund;
import io.github.laiwf94.payflow.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class PaymentProviderSimulatorTest {

  private final Faults faults = new Faults(new SplittableRandom(1));
  private final SimulatorSettings settings = new SimulatorSettings(Duration.ofSeconds(1));
  private final PaymentProviderSimulator sim = new PaymentProviderSimulator(faults, settings);

  private static <T> T answered(Simulated<T> s) {
    return ((Simulated.Answered<T>) s).value();
  }

  @Test
  void authorizeCaptureRefund() {
    Payment p = answered(sim.authorize("k1", "order-1", 2500, "EUR", "pm_card_ok"));
    assertThat(p.status()).isEqualTo(AUTHORIZED);
    Payment c = answered(sim.capture("k2", p.id(), 2500));
    assertThat(c.status()).isEqualTo(CAPTURED);
    assertThat(c.captured()).isEqualTo(2500);
    Refund r = answered(sim.refund("k3", p.id(), 1000, "first"));
    assertThat(r).isEqualTo(new Refund("re_00000001", p.id(), "first", 1000));
    assertThat(sim.paymentsFor("order-1").get(0).refunded()).isEqualTo(1000);
    assertThat(answered(sim.refund("k3", p.id(), 1000, "first")))
        .as("a replay is the same refund")
        .isEqualTo(r);
    assertThatThrownBy(() -> answered(sim.refund("k3", p.id(), 1000, "other")))
        .hasMessageContaining("different parameters");
    assertThatThrownBy(() -> answered(sim.refund("k4", p.id(), 1501, "too-much")))
        .isInstanceOf(SimulatorException.class)
        .hasMessageContaining("exceeds");
    assertThat(sim.ledgerFor("order-1"))
        .extracting(PaymentProviderSimulator.LedgerEntry::operation)
        .containsExactly("authorize", "capture", "refund");
    Refund second = answered(sim.refund("k5", p.id(), 1500, "second"));
    assertThat(answered(sim.findRefunds(p.id()))).containsExactly(r, second);
    assertThat(answered(sim.findRefunds(p.id())))
        .extracting(Refund::reference)
        .containsExactly("first", "second");
    assertThatThrownBy(() -> sim.findRefunds("pay_missing")).hasMessageContaining("no such");
    assertThatThrownBy(() -> sim.refund("k6", p.id(), 1, " ")).hasMessageContaining("reference");
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
    answered(sim.refund("ref", p.id(), 100, "r"));
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

  private static final Duration DAY = Duration.ofDays(1);

  /** A simulator on a clock the test moves, whose authorizations last a day. */
  private PaymentProviderSimulator timed(MutableClock clock, Faults f) {
    return new PaymentProviderSimulator(
        f, new SimulatorSettings(Duration.ofSeconds(1), DAY), clock);
  }

  @Test
  void anAuthorizationAdvertisesItsExpiryAndLapsesThen() {
    MutableClock clock = new MutableClock(Instant.parse("2026-11-20T10:00:00Z"));
    PaymentProviderSimulator sim = timed(clock, faults);
    Payment p = answered(sim.authorize("a", "order-1", 2500, "EUR", "pm_card_ok"));
    assertThat(p.expiresAt()).isEqualTo(Instant.parse("2026-11-21T10:00:00Z"));
    Payment declined = answered(sim.authorize("d", "order-2", 100, "EUR", "pm_card_declined"));
    assertThat(declined.expiresAt()).as("a decline holds nothing").isNull();

    clock.advance(DAY.minusNanos(1));
    assertThat(sim.paymentsFor("order-1")).extracting(Payment::status).containsExactly(AUTHORIZED);
    clock.advance(Duration.ofNanos(1));
    assertThat(sim.paymentsFor("order-1")).extracting(Payment::status).containsExactly(EXPIRED);
    assertThat(sim.paymentsFor("order-1").get(0).expiresAt()).isEqualTo(p.expiresAt());
    assertThat(answered(sim.authorize("a", "order-1", 2500, "EUR", "pm_card_ok")).status())
        .as("a replay shows the payment as it is now")
        .isEqualTo(EXPIRED);

    assertThatThrownBy(() -> sim.capture("c", p.id(), 2500))
        .isInstanceOfSatisfying(
            SimulatorException.class,
            e -> {
              assertThat(e.status()).isEqualTo(409);
              assertThat(e.code()).isEqualTo("authorization_expired");
            });
    assertThatThrownBy(() -> sim.reauthorize("r", p.id()))
        .isInstanceOfSatisfying(
            SimulatorException.class, e -> assertThat(e.code()).isEqualTo("authorization_expired"));
    Payment voided = answered(sim.voidAuthorization("v", p.id()));
    assertThat(voided.status())
        .as("nothing is held, which is what a void wants")
        .isEqualTo(EXPIRED);
    assertThat(sim.ledgerFor("order-1"))
        .extracting(PaymentProviderSimulator.LedgerEntry::operation)
        .containsExactly("authorize");
    assertThat(sim.expireNow(p.id())).as("nothing left to expire").isFalse();
  }

  @Test
  void aReauthorizationExtendsALiveHoldByAWholeLifetimeFromNow() {
    MutableClock clock = new MutableClock(Instant.parse("2026-11-20T10:00:00Z"));
    PaymentProviderSimulator sim = timed(clock, faults);
    Payment p = answered(sim.authorize("a", "order-1", 2500, "EUR", "pm_card_ok"));
    clock.advance(Duration.ofHours(20));
    Payment r = answered(sim.reauthorize("r", p.id()));
    assertThat(r.status()).isEqualTo(AUTHORIZED);
    assertThat(r.amount()).isEqualTo(2500);
    assertThat(r.expiresAt()).isEqualTo(Instant.parse("2026-11-22T06:00:00Z"));
    assertThat(answered(sim.reauthorize("r", p.id())))
        .as("a replay extends nothing more")
        .isEqualTo(r);

    clock.advance(Duration.ofHours(10));
    assertThat(sim.paymentsFor("order-1").get(0).status())
        .as("past the first expiry, inside the second")
        .isEqualTo(AUTHORIZED);
    Payment c = answered(sim.capture("c", p.id(), 2500));
    assertThat(c.status()).isEqualTo(CAPTURED);
    assertThat(c.expiresAt()).as("a capture holds nothing").isNull();
    clock.advance(Duration.ofDays(30));
    assertThat(sim.paymentsFor("order-1").get(0).status())
        .as("a capture never lapses")
        .isEqualTo(CAPTURED);
    assertThatThrownBy(() -> sim.reauthorize("r2", p.id()))
        .isInstanceOfSatisfying(
            SimulatorException.class, e -> assertThat(e.code()).isEqualTo("invalid_state"));
    assertThatThrownBy(() -> sim.reauthorize("r3", "pay_missing"))
        .hasMessageContaining("no such payment");
    assertThat(sim.ledgerFor("order-1"))
        .extracting(PaymentProviderSimulator.LedgerEntry::operation)
        .containsExactly("authorize", "reauthorize", "capture");
  }

  @Test
  void theIssuerCanDropAHoldNowWhateverItsAdvertisedExpiry() {
    MutableClock clock = new MutableClock(Instant.parse("2026-11-20T10:00:00Z"));
    PaymentProviderSimulator sim = timed(clock, faults);
    Payment p = answered(sim.authorize("a", "order-1", 2500, "EUR", "pm_card_ok"));
    assertThat(sim.expireNow(p.id())).isTrue();
    assertThat(sim.paymentsFor("order-1").get(0).status()).isEqualTo(EXPIRED);
    assertThat(sim.paymentsFor("order-1").get(0).expiresAt())
        .as("the advertised expiry doesn't show it")
        .isEqualTo(p.expiresAt());
    assertThat(sim.expireNow("pay_missing")).isFalse();
    Payment v = answered(sim.authorize("b", "order-2", 100, "EUR", "pm_card_ok"));
    answered(sim.voidAuthorization("v", v.id()));
    assertThat(sim.expireNow(v.id())).as("a voided payment holds nothing").isFalse();
  }

  @Test
  void earlyExpiryLapsesHoldsAtARandomMomentBeforeTheirAdvertisedExpiry() {
    MutableClock clock = new MutableClock(Instant.parse("2026-11-20T10:00:00Z"));
    Faults f = new Faults(new SplittableRandom(7));
    f.set("payment.authorize", new FaultPlan(0, 0, 0, Duration.ZERO, List.of(), 1.0));
    f.set("payment.reauthorize", new FaultPlan(0, 0, 0, Duration.ZERO, List.of(), 1.0));
    PaymentProviderSimulator sim = timed(clock, f);
    int n = 200;
    for (int i = 0; i < n; i++) {
      Payment p = answered(sim.authorize("a" + i, "order-" + i, 100, "EUR", "pm_card_ok"));
      assertThat(p.status()).isEqualTo(AUTHORIZED);
      assertThat(p.expiresAt()).isEqualTo(clock.instant().plus(DAY));
    }
    // Every hold lapses before its advertised day; spread over it, not all at once.
    int[] lapsedByHour = new int[24];
    for (int h = 0; h < 24; h++) {
      clock.advance(Duration.ofHours(1));
      lapsedByHour[h] = (int) sim.payments().stream().filter(p -> p.status() == EXPIRED).count();
    }
    assertThat(lapsedByHour[23]).isEqualTo(n);
    assertThat(lapsedByHour[5]).isBetween(n / 8, n / 2);
    assertThat(lapsedByHour[17]).isBetween(n / 2, n);

    // Rate zero for authorizations: this one lasts as advertised. A re-authorization rolls its
    // own plan, at one here, so the extended hold lapses before its new expiry.
    f.set("payment.authorize", FaultPlan.NONE);
    Payment q = answered(sim.authorize("c", "order-c", 100, "EUR", "pm_card_ok"));
    clock.advance(DAY.minusNanos(1));
    assertThat(sim.paymentsFor("order-c").get(0).status()).isEqualTo(AUTHORIZED);
    Payment r = answered(sim.reauthorize("r", q.id()));
    assertThat(r.expiresAt()).isEqualTo(clock.instant().plus(DAY));
    clock.advance(DAY.minusNanos(1));
    assertThat(sim.paymentsFor("order-c").get(0).status()).isEqualTo(EXPIRED);
  }

  @Test
  void anEarlyExpiryRateIsAProbabilityOnItsOwn() {
    assertThat(new FaultPlan(0.5, 0.25, 0.25, Duration.ZERO, List.of(), 1.0).earlyExpiryRate())
        .as("it is drawn apart from the call faults, so it doesn't count toward their sum")
        .isEqualTo(1.0);
    for (double bad : new double[] {-0.1, 1.1, Double.NaN}) {
      assertThatThrownBy(() -> new FaultPlan(0, 0, 0, Duration.ZERO, List.of(), bad))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(new FaultPlan(0, 0, 0, Duration.ZERO, List.of()).earlyExpiryRate()).isZero();
    assertThat(faults.earlyExpiry("payment.authorize")).isEmpty();
    assertThatThrownBy(() -> new SimulatorSettings(Duration.ZERO, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SimulatorSettings(Duration.ZERO, Duration.ofDays(32)))
        .isInstanceOf(IllegalArgumentException.class);
    SimulatorSettings s = new SimulatorSettings(Duration.ZERO);
    assertThat(s.authorizationLifetime()).isEqualTo(Duration.ofDays(7));
    s.authorizationLifetime(Duration.ofMillis(1));
    s.resetAuthorizationLifetime();
    assertThat(s.authorizationLifetime()).isEqualTo(Duration.ofDays(7));
  }
}
