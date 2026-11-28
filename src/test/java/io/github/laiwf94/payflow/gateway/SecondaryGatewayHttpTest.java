package io.github.laiwf94.payflow.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.laiwf94.payflow.money.Money;
import io.github.laiwf94.payflow.sim.Fault;
import io.github.laiwf94.payflow.sim.FaultPlan;
import io.github.laiwf94.payflow.sim.Faults;
import io.github.laiwf94.payflow.sim.PaymentProviderSimulator;
import io.github.laiwf94.payflow.sim.SimulatorConfig;
import io.github.laiwf94.payflow.sim.SimulatorKey;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The second provider's adapter against its in-process simulator, over real sockets: its own wire
 * format and error codes in, the same {@link CallResult}s the first provider gives out. The
 * properties match {@link GatewayHttpTest}'s, so both share one application context.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "payflow.simulator.answer-delay=700ms",
      "payflow.dependencies.payment.read-timeout=300ms",
      "payflow.dependencies.secondary.read-timeout=300ms",
      "payflow.dependencies.inventory.read-timeout=300ms",
      "payflow.dependencies.shipping.read-timeout=300ms"
    })
class SecondaryGatewayHttpTest {

  @Autowired PaymentProviders providers;
  @Autowired Faults faults;
  @Autowired PaymentProviderSimulator primarySim;

  @Autowired
  @Qualifier(SimulatorConfig.SECONDARY)
  PaymentProviderSimulator secondarySim;

  @Autowired SimulatorKey simKey;
  @Autowired ObjectMapper json;
  @LocalServerPort int port;

  private PaymentGateway payments;

  @BeforeEach
  void reset() {
    faults.reset(new SplittableRandom(1));
    primarySim.reset();
    secondarySim.reset();
    payments = providers.get(PaymentProviders.SECONDARY);
  }

  private static <T> T success(CallResult<T> r) {
    assertThat(r).isInstanceOf(CallResult.Success.class);
    return ((CallResult.Success<T>) r).value();
  }

  private static String rejected(CallResult<?> r) {
    assertThat(r).isInstanceOf(CallResult.Rejected.class);
    return ((CallResult.Rejected<?>) r).code();
  }

  @Test
  void bothProvidersAreConfiguredWithTheSimulatorOn() {
    assertThat(providers.names()).containsExactlyInAnyOrder("primary", "secondary");
    assertThat(payments).isInstanceOf(HttpSecondaryPaymentGateway.class);
  }

  @Test
  void aPaymentRunsItsWholeLifeAtTheSecondProviderAndNoneOfItAtTheFirst() {
    PaymentInfo auth =
        success(payments.authorize("a-1", "o1", Money.of(2500, "EUR"), "pm_card_ok"));
    assertThat(auth.id()).startsWith("psp_");
    assertThat(auth.reference()).isEqualTo("o1");
    assertThat(auth.amount()).isEqualTo(2500);
    assertThat(auth.currency()).isEqualTo("EUR");
    assertThat(auth.status()).isEqualTo(PaymentStatus.AUTHORIZED);
    assertThat(auth.expiresAt()).isNotNull();
    assertThat(auth.declineCode()).isNull();

    PaymentInfo extended = success(payments.reauthorize("x-1", auth.id()));
    assertThat(extended.status()).isEqualTo(PaymentStatus.AUTHORIZED);
    assertThat(extended.expiresAt()).isAfterOrEqualTo(auth.expiresAt());

    PaymentInfo cap = success(payments.capture("c-1", auth.id(), 2500));
    assertThat(cap.status()).isEqualTo(PaymentStatus.CAPTURED);
    assertThat(cap.captured()).isEqualTo(2500);
    assertThat(cap.expiresAt()).isNull();

    RefundInfo first = success(payments.refund("r-1", auth.id(), 1000, "refund-a"));
    assertThat(first.id()).startsWith("psp_rf_");
    assertThat(first.paymentId()).isEqualTo(auth.id());
    assertThat(first.reference()).isEqualTo("refund-a");
    assertThat(first.amount()).isEqualTo(1000);
    assertThat(success(payments.refund("r-1", auth.id(), 1000, "refund-a")))
        .as("a replay with the same key")
        .isEqualTo(first);
    RefundInfo second = success(payments.refund("r-2", auth.id(), 1500, "refund-b"));
    assertThat(success(payments.findRefunds(auth.id()))).containsExactly(first, second);
    PaymentInfo found = success(payments.findByReference("o1")).getFirst();
    assertThat(found.refunded()).isEqualTo(2500);
    assertThat(found.netCaptured()).isZero();
    assertThat(success(payments.findByReference("nothing"))).isEmpty();

    PaymentInfo other = success(payments.authorize("a-2", "o2", Money.of(10, "EUR"), "pm_card_ok"));
    assertThat(success(payments.voidAuthorization("v-2", other.id())).status())
        .isEqualTo(PaymentStatus.VOIDED);

    assertThat(secondarySim.ledger())
        .extracting(PaymentProviderSimulator.LedgerEntry::operation)
        .containsExactly(
            "authorize", "reauthorize", "capture", "refund", "refund", "authorize", "void");
    assertThat(primarySim.ledger()).as("the first provider saw none of it").isEmpty();
  }

  @Test
  void aRefusalIsARejectionWithTheFirstProvidersDeclineCode() {
    assertThat(rejected(payments.authorize("d1", "d1", Money.of(10, "EUR"), "pm_card_declined")))
        .isEqualTo("card_declined");
    assertThat(
            rejected(
                payments.authorize("d2", "d2", Money.of(10, "EUR"), "pm_card_insufficient_funds")))
        .isEqualTo("insufficient_funds");
    assertThat(rejected(payments.authorize("d3", "d3", Money.of(10, "EUR"), "pm_card_expired")))
        .isEqualTo("expired_card");
    PaymentInfo declined = success(payments.findByReference("d2")).getFirst();
    assertThat(declined.status()).isEqualTo(PaymentStatus.DECLINED);
    assertThat(declined.declineCode()).isEqualTo("insufficient_funds");
    assertThat(declined.expiresAt()).isNull();
  }

  @Test
  void itsErrorCodesBecomeTheCodesTheStepsActOn() {
    PaymentInfo auth = success(payments.authorize("a", "o1", Money.of(700, "EUR"), "pm_card_ok"));
    assertThat(rejected(payments.capture("c0", auth.id(), 701))).isEqualTo("invalid_amount");
    success(payments.capture("c1", auth.id(), 700));
    assertThat(rejected(payments.capture("c2", auth.id(), 700))).isEqualTo("already_captured");
    assertThat(rejected(payments.reauthorize("x", auth.id()))).isEqualTo("invalid_state");
    assertThat(rejected(payments.refund("r", auth.id(), 701, "too-much")))
        .isEqualTo("invalid_amount");
    assertThat(rejected(payments.capture("c3", "psp_missing", 10))).isEqualTo("not_found");
    assertThat(rejected(payments.findRefunds("psp_missing"))).isEqualTo("not_found");
    assertThat(rejected(payments.authorize("a", "o1", Money.of(701, "EUR"), "pm_card_ok")))
        .as("the same key with other parameters")
        .isEqualTo("idempotency_error");

    PaymentInfo held = success(payments.authorize("b", "o2", Money.of(700, "EUR"), "pm_card_ok"));
    assertThat(secondarySim.expireNow(held.id())).isTrue();
    assertThat(success(payments.findByReference("o2")).getFirst().status())
        .isEqualTo(PaymentStatus.EXPIRED);
    assertThat(rejected(payments.capture("c4", held.id(), 700))).isEqualTo("authorization_expired");
    assertThat(rejected(payments.reauthorize("x4", held.id()))).isEqualTo("authorization_expired");
    assertThat(success(payments.voidAuthorization("v4", held.id())).status())
        .as("nothing left to drop is what a void wants")
        .isEqualTo(PaymentStatus.EXPIRED);
  }

  @Test
  void outagesAndLostAnswersAreClassifiedAsForTheFirstProvider() {
    faults.set(
        "secondary.authorize",
        new FaultPlan(0, 0, 0, Duration.ZERO, List.of(Fault.ERROR, Fault.UNKNOWN_AFTER)));
    assertThat(payments.authorize("a", "o1", Money.of(10, "EUR"), "pm_card_ok"))
        .isInstanceOf(CallResult.Transient.class);
    assertThat(secondarySim.ledger()).as("a 503 applied nothing").isEmpty();
    assertThat(payments.authorize("a", "o1", Money.of(10, "EUR"), "pm_card_ok"))
        .isInstanceOf(CallResult.Unknown.class);
    PaymentInfo made = success(payments.findByReference("o1")).getFirst();
    assertThat(made.status()).isEqualTo(PaymentStatus.AUTHORIZED);
    assertThat(success(payments.authorize("a", "o1", Money.of(10, "EUR"), "pm_card_ok")).id())
        .as("the retry with the same key is the payment the lost answer made")
        .isEqualTo(made.id());
    assertThat(secondarySim.ledger()).hasSize(1);
  }

  @Test
  void eachProviderHasItsOwnFaultPlans() {
    faults.set("payment.authorize", new FaultPlan(1, 0, 0, Duration.ZERO, List.of()));
    assertThat(payments.authorize("a", "o1", Money.of(10, "EUR"), "pm_card_ok"))
        .isInstanceOf(CallResult.Success.class);
    assertThat(providers.get("primary").authorize("a", "o1", Money.of(10, "EUR"), "pm_card_ok"))
        .isInstanceOf(CallResult.Transient.class);
    faults.set("secondary.lookup", new FaultPlan(1, 0, 0, Duration.ZERO, List.of()));
    assertThat(payments.findByReference("o1")).isInstanceOf(CallResult.Transient.class);
    assertThat(providers.get("primary").findByReference("o1"))
        .isInstanceOf(CallResult.Success.class);
  }

  @Test
  void itsWireFormatIsItsOwn() throws Exception {
    HttpResponse<String> ok =
        post(
            "/sim/secondary/payments",
            "{\"merchantReference\":\"w1\",\"amount\":{\"value\":10,\"currency\":\"EUR\"},"
                + "\"paymentMethod\":{\"type\":\"scheme\",\"token\":\"pm_card_declined\"}}");
    assertThat(ok.statusCode()).as("a refusal is not an HTTP error").isEqualTo(200);
    JsonNode refused = json.readTree(ok.body());
    assertThat(refused.path("resultCode").asString()).isEqualTo("Refused");
    assertThat(refused.path("refusalReasonCode").asString()).isEqualTo("2");
    assertThat(refused.path("status").asString()).isEqualTo("refused");
    assertThat(refused.path("amount").path("value").asLong()).isEqualTo(10);
    assertThat(refused.has("merchantReference")).isTrue();

    HttpResponse<String> err =
        post(
            "/sim/secondary/payments/" + refused.path("pspReference").asString() + "/captures",
            "{\"amount\":{\"value\":10}}");
    assertThat(err.statusCode()).isEqualTo(422);
    JsonNode e = json.readTree(err.body());
    assertThat(e.path("status").asInt()).isEqualTo(422);
    assertThat(e.path("errorCode").asString()).isEqualTo("167");
    assertThat(e.path("errorType").asString()).isEqualTo("business");
    assertThat(e.has("error")).as("not the first provider's nested format").isFalse();

    HttpResponse<String> bad = post("/sim/secondary/payments", "{\"merchant_reference\":\"x\"}");
    assertThat(bad.statusCode()).isEqualTo(400);
    assertThat(json.readTree(bad.body()).path("errorCode").asString()).isEqualTo("100");

    faults.set("secondary.capture", new FaultPlan(1, 0, 0, Duration.ZERO, List.of()));
    HttpResponse<String> down =
        post("/sim/secondary/payments/psp_1/captures", "{\"amount\":{\"value\":1}}");
    assertThat(down.statusCode()).isEqualTo(503);
    assertThat(json.readTree(down.body()).path("errorCode").asString()).isEqualTo("905");
  }

  @Test
  void anAnswerItWouldNeverSendIsAnErrorNotAGuess() {
    JsonNode unknownStatus =
        json.readTree(
            "{\"pspReference\":\"psp_1\",\"merchantReference\":\"o\",\"amount\":{\"value\":1,"
                + "\"currency\":\"EUR\"},\"status\":\"pending\"}");
    assertThatThrownBy(() -> HttpSecondaryPaymentGateway.payment(unknownStatus))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("status pending");
    JsonNode noId = json.readTree("{\"merchantReference\":\"o\",\"status\":\"captured\"}");
    assertThatThrownBy(() -> HttpSecondaryPaymentGateway.payment(noId))
        .hasMessageContaining("no pspReference");
    JsonNode badTime =
        json.readTree(
            "{\"pspReference\":\"psp_1\",\"merchantReference\":\"o\",\"amount\":{\"value\":1,"
                + "\"currency\":\"EUR\"},\"status\":\"authorised\",\"expiresAt\":\"soon\"}");
    assertThatThrownBy(() -> HttpSecondaryPaymentGateway.payment(badTime))
        .hasMessageContaining("unreadable expiresAt");
    assertThat(
            ((CallResult.Rejected<?>)
                    HttpSecondaryPaymentGateway.rejected(
                        json.readTree("{\"errorCode\":\"999\",\"message\":\"m\"}"), "fallback"))
                .code())
        .as("a code it has never documented keeps its origin")
        .isEqualTo("secondary_999");
    assertThat(
            ((CallResult.Rejected<?>)
                    HttpSecondaryPaymentGateway.rejected(json.readTree("{}"), "fallback"))
                .code())
        .isEqualTo("fallback");
  }

  private HttpResponse<String> post(String path, String body) throws Exception {
    try (HttpClient client = HttpClient.newHttpClient()) {
      return client.send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
              .header("Authorization", "Bearer " + simKey.value())
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(body))
              .build(),
          HttpResponse.BodyHandlers.ofString());
    }
  }
}
