package io.github.laiwf94.payflow.routing;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Chooses the payment provider of each tender of a new order from {@link RoutingProperties}: the
 * first rule whose conditions on currency, payment method and merchant all hold, or the default
 * provider. It is a pure function of the configuration and the tender, evaluated once per tender in
 * the order's insert transaction; the decision is stored with the tender and the saga never routes
 * again.
 *
 * <p>The configuration is checked when the router is built, so a mistake stops the process at
 * startup instead of misrouting orders: every rule names a configured provider, has a unique id
 * that a tender can record, sets at least one condition, and every condition is well formed.
 *
 * <p>Each decision counts on {@code payflow.routing.decisions{provider, rule}}.
 */
public final class Router {

  /** The rule recorded for a tender no rule matched. */
  public static final String DEFAULT_RULE = "default";

  public static final int MAX_RULES = 100;

  /** What {@code payments_routing_rule_format} allows. */
  static final Pattern RULE_ID = Pattern.compile("[a-z][a-z0-9_-]{0,63}");

  static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

  /** A method, or a method prefix followed by {@code *}. */
  static final Pattern METHOD = Pattern.compile("[A-Za-z0-9_-]{1,128}\\*?");

  /** What a merchant id is (see ApiKeys). */
  static final Pattern MERCHANT = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");

  private record Compiled(
      String id,
      String provider,
      Set<String> currencies,
      Set<String> methods,
      List<String> methodPrefixes,
      Set<String> merchants) {

    boolean matches(String merchantId, String currency, String method) {
      return (currencies.isEmpty() || currencies.contains(currency))
          && (merchants.isEmpty() || merchants.contains(merchantId))
          && (noMethodCondition()
              || methods.contains(method)
              || methodPrefixes.stream().anyMatch(method::startsWith));
    }

    boolean noMethodCondition() {
      return methods.isEmpty() && methodPrefixes.isEmpty();
    }
  }

  private final List<Compiled> rules;
  private final RoutingDecision fallback;
  private final Map<RoutingDecision, Counter> counters = new HashMap<>();

  /**
   * @param providers the providers this instance can call
   * @throws IllegalArgumentException when the configuration is not one it can route by
   */
  public Router(RoutingProperties props, Set<String> providers, MeterRegistry meters) {
    if (!providers.contains(props.defaultProvider())) {
      throw new IllegalArgumentException(
          "payflow.routing.default-provider "
              + props.defaultProvider()
              + " is not a configured provider "
              + providers);
    }
    if (props.rules().size() > MAX_RULES) {
      throw new IllegalArgumentException("payflow.routing.rules is limited to " + MAX_RULES);
    }
    List<Compiled> compiled = new ArrayList<>();
    Set<String> ids = new HashSet<>();
    for (int i = 0; i < props.rules().size(); i++) {
      compiled.add(compile(i, props.rules().get(i), providers, ids));
    }
    this.rules = List.copyOf(compiled);
    this.fallback = new RoutingDecision(props.defaultProvider(), DEFAULT_RULE);
    counter(meters, fallback);
    for (Compiled c : rules) {
      counter(meters, new RoutingDecision(c.provider(), c.id()));
    }
  }

  /**
   * Where a tender of {@code merchantId}'s order, in {@code currency} and paid with {@code method},
   * goes.
   */
  public RoutingDecision route(String merchantId, String currency, String method) {
    RoutingDecision d = fallback;
    for (Compiled c : rules) {
      if (c.matches(merchantId, currency, method)) {
        d = new RoutingDecision(c.provider(), c.id());
        break;
      }
    }
    Counter n = counters.get(d);
    if (n != null) {
      n.increment();
    }
    return d;
  }

  /** Where a tender goes when no rule matches it. */
  public RoutingDecision fallback() {
    return fallback;
  }

  /** How many rules there are, for the startup log. */
  public int size() {
    return rules.size();
  }

  private static Compiled compile(
      int index, RoutingProperties.Rule r, Set<String> providers, Set<String> ids) {
    String where = "payflow.routing.rules[" + index + "]";
    if (r.id() == null || !RULE_ID.matcher(r.id()).matches() || DEFAULT_RULE.equals(r.id())) {
      throw new IllegalArgumentException(
          where + ".id must match " + RULE_ID + " and not be \"" + DEFAULT_RULE + "\"");
    }
    where = "routing rule " + r.id();
    if (!ids.add(r.id())) {
      throw new IllegalArgumentException(where + " is defined twice");
    }
    if (r.provider() == null || !providers.contains(r.provider())) {
      throw new IllegalArgumentException(
          where + " names provider " + r.provider() + ", not one of " + providers);
    }
    if (r.currencies().isEmpty() && r.methods().isEmpty() && r.merchants().isEmpty()) {
      throw new IllegalArgumentException(
          where + " sets no condition; use payflow.routing.default-provider for that");
    }
    for (String c : r.currencies()) {
      if (c == null || !CURRENCY.matcher(c).matches()) {
        throw new IllegalArgumentException(where + " has currency " + c + ", not [A-Z]{3}");
      }
    }
    Set<String> exact = new HashSet<>();
    List<String> prefixes = new ArrayList<>();
    for (String m : r.methods()) {
      if (m == null || !METHOD.matcher(m).matches() || m.equals("*")) {
        throw new IllegalArgumentException(
            where + " has method " + m + "; give a method or a prefix ending in *");
      }
      if (m.endsWith("*")) {
        prefixes.add(m.substring(0, m.length() - 1));
      } else {
        exact.add(m);
      }
    }
    for (String m : r.merchants()) {
      if (m == null || !MERCHANT.matcher(m).matches()) {
        throw new IllegalArgumentException(where + " has merchant " + m);
      }
    }
    return new Compiled(
        r.id(),
        r.provider(),
        Set.copyOf(r.currencies()),
        Set.copyOf(exact),
        List.copyOf(prefixes),
        Set.copyOf(r.merchants()));
  }

  private void counter(MeterRegistry meters, RoutingDecision d) {
    counters.computeIfAbsent(
        d,
        k ->
            Counter.builder("payflow.routing.decisions")
                .description("Tenders routed, by provider and the rule that chose it")
                .tag("provider", k.provider())
                .tag("rule", k.rule())
                .register(meters));
  }
}
