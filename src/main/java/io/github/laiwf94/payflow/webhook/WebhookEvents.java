package io.github.laiwf94.payflow.webhook;

import io.github.laiwf94.payflow.order.OrderState;
import io.github.laiwf94.payflow.order.OrderTransitions;
import io.github.laiwf94.payflow.refund.RefundTransitions;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The event types an endpoint can subscribe to: every type the outbox writes, named the way it
 * writes them, so the two can't drift apart. {@value #ALL} subscribes to every type, including ones
 * added later.
 */
public final class WebhookEvents {

  public static final String ALL = "*";

  /**
   * The type of a test ping, which goes to the one endpoint it was asked for whatever its filter
   * says, so it is not one of the {@link #TYPES} a filter can name.
   */
  public static final String PING = "ping";

  /** At most this many types in one endpoint's filter. */
  public static final int MAX_FILTER = 32;

  /** Every type, in a stable order: the order's, by state, then the refund's. */
  public static final Set<String> TYPES;

  static {
    Set<String> types = new LinkedHashSet<>();
    types.add(OrderTransitions.CREATED);
    types.add(OrderTransitions.CANCEL_REQUESTED);
    for (OrderState s : OrderState.values()) {
      // An order is created PENDING and never returns there, so no event announces it.
      if (s != OrderState.PENDING) {
        types.add(OrderTransitions.eventType(s));
      }
    }
    types.add(RefundTransitions.CREATED);
    types.add(RefundTransitions.SUCCEEDED);
    types.add(RefundTransitions.FAILED);
    TYPES = java.util.Collections.unmodifiableSet(types);
  }

  private WebhookEvents() {}

  /**
   * The filter as stored: duplicates dropped, first occurrence kept.
   *
   * @throws IllegalArgumentException when it is empty or too long, names an unknown type, or mixes
   *     {@value #ALL} with other types
   */
  public static List<String> normalize(List<String> filter) {
    if (filter == null || filter.isEmpty()) {
      throw new IllegalArgumentException("enabled_events: must name at least one event type");
    }
    Set<String> out = new LinkedHashSet<>();
    for (String t : filter) {
      if (t == null || (!ALL.equals(t) && !TYPES.contains(t))) {
        throw new IllegalArgumentException(
            "enabled_events: unknown event type "
                + (t == null ? "null" : quote(t))
                + "; use \"*\" or one of "
                + TYPES);
      }
      out.add(t);
    }
    if (out.size() > MAX_FILTER) {
      throw new IllegalArgumentException("enabled_events: at most " + MAX_FILTER + " event types");
    }
    if (out.contains(ALL) && out.size() > 1) {
      throw new IllegalArgumentException(
          "enabled_events: \"*\" already means every type; give it alone");
    }
    return new ArrayList<>(out);
  }

  /** Whether an endpoint with {@code filter} wants events of {@code type}. */
  public static boolean matches(List<String> filter, String type) {
    return filter.contains(ALL) || filter.contains(type);
  }

  /** Quotes untrusted text for a message, cut short so a long value can't bloat it. */
  private static String quote(String s) {
    String cut = s.length() > 64 ? s.substring(0, 64) + "..." : s;
    return "\"" + cut.replaceAll("[^\\x20-\\x7e]", "?") + "\"";
  }
}
