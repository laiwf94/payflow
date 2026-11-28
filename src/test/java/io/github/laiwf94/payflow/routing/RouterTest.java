package io.github.laiwf94.payflow.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.laiwf94.payflow.routing.RoutingProperties.Rule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Routing rules: the first that matches decides, and a configuration it can't route by is refused.
 */
class RouterTest {

  private static final Set<String> PROVIDERS = Set.of("primary", "secondary");

  private static Rule rule(
      String id,
      String provider,
      List<String> currencies,
      List<String> methods,
      List<String> merchants) {
    return new Rule(id, provider, currencies, methods, merchants);
  }

  private static Router router(String fallback, Rule... rules) {
    return new Router(
        new RoutingProperties(fallback, List.of(rules)), PROVIDERS, new SimpleMeterRegistry());
  }

  @Test
  void theFirstMatchingRuleDecidesAndEveryConditionItSetsMustHold() {
    Router r =
        router(
            "primary",
            rule("eur-cards", "secondary", List.of("EUR"), List.of("pm_card_*"), List.of()),
            rule("globex", "secondary", List.of(), List.of(), List.of("globex")),
            rule("gift-usd", "primary", List.of("USD"), List.of("pm_giftcard_ok"), List.of()));

    assertThat(r.route("acme", "EUR", "pm_card_visa"))
        .isEqualTo(new RoutingDecision("secondary", "eur-cards"));
    assertThat(r.route("globex", "EUR", "pm_card_visa"))
        .as("an earlier rule wins over a later one that also matches")
        .isEqualTo(new RoutingDecision("secondary", "eur-cards"));
    assertThat(r.route("globex", "USD", "pm_giftcard_ok"))
        .isEqualTo(new RoutingDecision("secondary", "globex"));
    assertThat(r.route("acme", "USD", "pm_giftcard_ok"))
        .isEqualTo(new RoutingDecision("primary", "gift-usd"));
    assertThat(r.route("acme", "USD", "pm_giftcard_ok_2"))
        .as("an exact method is not a prefix")
        .isEqualTo(r.fallback());
    assertThat(r.route("acme", "USD", "pm_card_visa"))
        .as("currency must match too")
        .isEqualTo(new RoutingDecision("primary", Router.DEFAULT_RULE));
    assertThat(r.route("acme", "EUR", "pm_giftcard_ok"))
        .as("method must match too")
        .isEqualTo(r.fallback());
    assertThat(r.route("acme", "EUR", "pm_car"))
        .as("a method shorter than the prefix doesn't match it")
        .isEqualTo(r.fallback());
    assertThat(r.size()).isEqualTo(3);
  }

  @Test
  void theDefaultProviderTakesWhatNoRuleMatches() {
    Router none = router("secondary");
    assertThat(none.route("acme", "EUR", "pm_card_visa"))
        .isEqualTo(new RoutingDecision("secondary", "default"));
    assertThat(none.size()).isZero();
  }

  @Test
  void everyDecisionIsCountedByProviderAndRule() {
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    Router r =
        new Router(
            new RoutingProperties(
                "primary", List.of(rule("eur", "secondary", List.of("EUR"), List.of(), List.of()))),
            PROVIDERS,
            meters);
    r.route("acme", "EUR", "pm_card_visa");
    r.route("acme", "EUR", "pm_card_visa");
    r.route("acme", "USD", "pm_card_visa");
    assertThat(
            meters
                .get("payflow.routing.decisions")
                .tag("provider", "secondary")
                .tag("rule", "eur")
                .counter()
                .count())
        .isEqualTo(2);
    assertThat(
            meters
                .get("payflow.routing.decisions")
                .tag("provider", "primary")
                .tag("rule", "default")
                .counter()
                .count())
        .isEqualTo(1);
  }

  @Test
  void aConfigurationThatCannotRouteIsRefusedAtStartup() {
    List<String> eur = List.of("EUR");
    List<String> none = List.of();
    assertThatThrownBy(() -> router("tertiary"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("default-provider tertiary");
    assertThatThrownBy(() -> router("primary", rule("x", "tertiary", eur, none, none)))
        .hasMessageContaining("names provider tertiary");
    assertThatThrownBy(() -> router("primary", rule("x", null, eur, none, none)))
        .hasMessageContaining("names provider null");
    assertThatThrownBy(
            () ->
                router(
                    "primary",
                    rule("x", "secondary", eur, none, none),
                    rule("x", "primary", List.of("USD"), none, none)))
        .hasMessageContaining("routing rule x is defined twice");
    for (String id :
        new String[] {null, "", "Eur", "1eur", "eur cards", "default", "a".repeat(65)}) {
      assertThatThrownBy(() -> router("primary", rule(id, "secondary", eur, none, none)))
          .as("id %s", id)
          .hasMessageContaining("rules[0].id must match");
    }
    assertThat(router("primary", rule("a".repeat(64), "secondary", eur, none, none)).size())
        .isEqualTo(1);
    assertThatThrownBy(() -> router("primary", rule("all", "secondary", none, none, none)))
        .hasMessageContaining("sets no condition");
    for (String c : new String[] {"eur", "EURO", "", "E1R"}) {
      assertThatThrownBy(() -> router("primary", rule("x", "secondary", List.of(c), none, none)))
          .as("currency %s", c)
          .hasMessageContaining("has currency");
    }
    for (String m : new String[] {"*", "", "pm card", "pm_*_x", "pm_card**", "a".repeat(129)}) {
      assertThatThrownBy(() -> router("primary", rule("x", "secondary", none, List.of(m), none)))
          .as("method %s", m)
          .hasMessageContaining("has method");
    }
    for (String m : new String[] {"", "Acme", "ac me", "-acme"}) {
      assertThatThrownBy(() -> router("primary", rule("x", "secondary", none, none, List.of(m))))
          .as("merchant %s", m)
          .hasMessageContaining("has merchant");
    }
    List<Rule> many = new ArrayList<>();
    for (int i = 0; i <= Router.MAX_RULES; i++) {
      many.add(rule("r" + i, "secondary", eur, none, none));
    }
    assertThatThrownBy(
            () ->
                new Router(
                    new RoutingProperties("primary", many), PROVIDERS, new SimpleMeterRegistry()))
        .hasMessageContaining("limited to " + Router.MAX_RULES);
    assertThat(
            new Router(
                    new RoutingProperties("primary", many.subList(0, Router.MAX_RULES)),
                    PROVIDERS,
                    new SimpleMeterRegistry())
                .size())
        .isEqualTo(Router.MAX_RULES);
  }

  @Test
  void aDecisionAlwaysNamesItsProviderAndRule() {
    assertThatThrownBy(() -> new RoutingDecision(null, "default"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RoutingDecision("primary", null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
