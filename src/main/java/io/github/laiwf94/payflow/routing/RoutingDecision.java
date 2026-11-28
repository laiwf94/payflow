package io.github.laiwf94.payflow.routing;

/**
 * Where one tender goes and why: {@code provider} because of rule {@code rule}, or {@value
 * Router#DEFAULT_RULE} when no rule matched. Stored on the tender when its order is created and
 * never changed, so every instance, before and after any restart or configuration change, calls the
 * provider that holds the tender's money.
 */
public record RoutingDecision(String provider, String rule) {

  public RoutingDecision {
    if (provider == null || rule == null) {
      throw new IllegalArgumentException("a routing decision names a provider and a rule");
    }
  }
}
