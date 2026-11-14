package io.github.laiwf94.payflow.webhook;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Which of an endpoint's deliveries a listing or a replay covers. Every field is optional; an empty
 * set of states means every state. Time bounds are on the delivery's {@code created_at}, which is
 * when the relay handed the event over, and are in microseconds as the database stores them.
 *
 * @param createdGte at or after this time
 * @param createdLt before this time
 */
public record DeliveryFilter(
    Set<DeliveryState> states, UUID eventId, Instant createdGte, Instant createdLt) {

  /** The states a delivery is in, as {@code webhook_deliveries.state} stores them. */
  public enum DeliveryState {
    PENDING,
    SUCCEEDED,
    FAILED
  }

  public static final DeliveryFilter ALL = new DeliveryFilter(Set.of(), null, null, null);

  public DeliveryFilter {
    states = states.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(states));
    if (createdGte != null && createdLt != null && !createdGte.isBefore(createdLt)) {
      throw new IllegalArgumentException("created_gte must be before created_lt");
    }
  }

  /** The filter in one stable form, which a listing cursor is signed over. */
  public String canonical() {
    return "states="
        + states.stream().map(Enum::name).sorted().collect(Collectors.joining(","))
        + "&event_id="
        + (eventId == null ? "" : eventId)
        + "&created_gte="
        + (createdGte == null ? "" : createdGte)
        + "&created_lt="
        + (createdLt == null ? "" : createdLt);
  }

  String[] stateNames() {
    return states.stream().map(Enum::name).sorted().toArray(String[]::new);
  }
}
