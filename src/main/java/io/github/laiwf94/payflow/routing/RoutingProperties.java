package io.github.laiwf94.payflow.routing;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Which payment provider each tender of a new order goes to ({@code payflow.routing.*}). The first
 * rule that matches a tender decides; a tender no rule matches goes to {@code defaultProvider}.
 *
 * <pre>
 * payflow:
 *   routing:
 *     default-provider: primary
 *     rules:
 *       - id: eur-cards
 *         provider: secondary
 *         currencies: [EUR]
 *         methods: [pm_card_*]
 *       - id: globex-all
 *         provider: secondary
 *         merchants: [globex]
 * </pre>
 *
 * @param defaultProvider where a tender that no rule matches goes
 * @param rules tried in order; see {@link Rule}
 */
@ConfigurationProperties("payflow.routing")
public record RoutingProperties(
    @DefaultValue("primary") String defaultProvider, @DefaultValue List<Rule> rules) {

  /**
   * One routing rule. A tender matches when it matches every condition the rule sets; an empty list
   * sets no condition, but a rule must set at least one.
   *
   * @param id recorded on each tender it routes ({@code payments.routing_rule}), so it should stay
   *     the same while the rule means the same thing
   * @param provider a configured provider name
   * @param currencies ISO 4217 codes the tender's amount may be in
   * @param methods payment methods, each exact or a prefix ending in {@code *} ({@code pm_card_*})
   * @param merchants merchant ids the order may belong to
   */
  public record Rule(
      String id,
      String provider,
      @DefaultValue List<String> currencies,
      @DefaultValue List<String> methods,
      @DefaultValue List<String> merchants) {}
}
