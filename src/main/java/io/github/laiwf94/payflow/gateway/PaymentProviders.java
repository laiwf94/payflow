package io.github.laiwf94.payflow.gateway;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The payment providers this instance can reach, by the name a tender records ({@code
 * payments.provider}). A tender is routed once, when its order is created, and every later call for
 * it, from any instance and after any restart, goes to the provider it names here.
 *
 * <p>{@value #PRIMARY} is always present. {@value #SECONDARY} is present when it is configured
 * ({@code payflow.dependencies.secondary.*}) or the in-process simulator runs.
 */
public final class PaymentProviders {

  /** The first provider, {@code payflow.dependencies.payment}. */
  public static final String PRIMARY = "primary";

  /** The second provider, {@code payflow.dependencies.secondary}. */
  public static final String SECONDARY = "secondary";

  /** What {@code payments_provider_format} allows. */
  static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_-]{0,31}");

  private final Map<String, PaymentGateway> byName;

  /**
   * @throws IllegalArgumentException without {@value #PRIMARY}, or with a name a tender can't
   *     record
   */
  public PaymentProviders(Map<String, PaymentGateway> byName) {
    if (!byName.containsKey(PRIMARY)) {
      throw new IllegalArgumentException("the " + PRIMARY + " payment provider is required");
    }
    for (String name : byName.keySet()) {
      if (!NAME.matcher(name).matches()) {
        throw new IllegalArgumentException("provider name " + name + " is not [a-z][a-z0-9_-]*");
      }
    }
    this.byName = new LinkedHashMap<>(byName);
  }

  /** Only the first provider. */
  public static PaymentProviders of(PaymentGateway primary) {
    return new PaymentProviders(Map.of(PRIMARY, primary));
  }

  /**
   * The provider a tender names.
   *
   * @throws IllegalStateException when this instance doesn't know it: configuration lost a provider
   *     that tenders still use, so the step fails and retries until it is put back
   */
  public PaymentGateway get(String name) {
    PaymentGateway g = byName.get(name);
    if (g == null) {
      throw new IllegalStateException(
          "payment provider " + name + " is not configured on this instance");
    }
    return g;
  }

  public Set<String> names() {
    return Set.copyOf(byName.keySet());
  }

  public boolean has(String name) {
    return byName.containsKey(name);
  }
}
