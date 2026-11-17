package io.github.laiwf94.payflow.timer;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The timer kinds this instance knows, each with its handler. A kind may only be scheduled, and is
 * only claimed, where its handler is: during a rolling deploy, timers of a kind that only the new
 * version has wait for a new instance instead of failing on an old one.
 */
public final class TimerHandlers {

  /** The pattern {@code V23__timers.sql} enforces as well. */
  static final Pattern KIND = Pattern.compile("[a-z][a-z0-9_.]{0,62}");

  private final Map<String, TimerHandler> byKind;

  public TimerHandlers(Collection<? extends TimerHandler> handlers) {
    Map<String, TimerHandler> m = new LinkedHashMap<>();
    for (TimerHandler h : handlers) {
      String kind = h.kind();
      if (kind == null || !KIND.matcher(kind).matches()) {
        throw new IllegalArgumentException("timer kind must match " + KIND + ": " + kind);
      }
      TimerHandler other = m.putIfAbsent(kind, h);
      if (other != null) {
        throw new IllegalStateException(
            "two handlers for timer kind "
                + kind
                + ": "
                + other.getClass().getName()
                + " and "
                + h.getClass().getName());
      }
    }
    this.byKind = Map.copyOf(m);
  }

  public static TimerHandlers of(TimerHandler... handlers) {
    return new TimerHandlers(List.of(handlers));
  }

  public Set<String> kinds() {
    return byKind.keySet();
  }

  public boolean isEmpty() {
    return byKind.isEmpty();
  }

  Optional<TimerHandler> get(String kind) {
    return Optional.ofNullable(byKind.get(kind));
  }

  /** Throws unless {@code kind} has a handler here. */
  void require(String kind) {
    if (!byKind.containsKey(kind)) {
      throw new IllegalArgumentException("no handler for timer kind " + kind);
    }
  }
}
