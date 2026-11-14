package io.github.laiwf94.payflow.api;

import io.github.laiwf94.payflow.webhook.DeliveryFilter;
import io.github.laiwf94.payflow.webhook.DeliveryFilter.DeliveryState;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The query string of {@code GET /v1/webhook_endpoints/{id}/deliveries}, read as strictly as the
 * order listing's: an unknown parameter, a repeated one (other than {@code state}) or a malformed
 * value is a 400 rather than a quietly wider listing.
 */
record WebhookDeliveryListRequest(DeliveryFilter filter, int limit, String cursor) {

  private static final List<String> KNOWN =
      List.of("limit", "cursor", "state", "event_id", "created_gte", "created_lt");
  private static final Pattern LIMIT = Pattern.compile("[0-9]{1,3}");
  private static final Pattern UUID_TEXT =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  private static final Pattern COMMA = Pattern.compile(",");

  static WebhookDeliveryListRequest parse(Map<String, String[]> params) {
    for (String name : params.keySet()) {
      if (!KNOWN.contains(name)) {
        throw new IllegalArgumentException(
            "unknown query parameter "
                + OrderListRequest.printable(name)
                + "; known: "
                + String.join(", ", KNOWN));
      }
    }
    String limitText = OrderListRequest.single(params, "limit");
    int limit = OrderListRequest.DEFAULT_LIMIT;
    if (limitText != null) {
      if (!LIMIT.matcher(limitText).matches()
          || (limit = Integer.parseInt(limitText)) < 1
          || limit > OrderListRequest.MAX_LIMIT) {
        throw new IllegalArgumentException(
            "limit: must be an integer from 1 to " + OrderListRequest.MAX_LIMIT);
      }
    }
    Set<DeliveryState> states = EnumSet.noneOf(DeliveryState.class);
    for (String value : params.getOrDefault("state", new String[0])) {
      for (String name : COMMA.split(value, -1)) {
        try {
          states.add(DeliveryState.valueOf(name));
        } catch (IllegalArgumentException e) {
          throw new IllegalArgumentException(
              "state: unknown state "
                  + OrderListRequest.printable(name)
                  + "; known: PENDING, SUCCEEDED, FAILED",
              e);
        }
      }
    }
    String eventText = OrderListRequest.single(params, "event_id");
    UUID eventId = null;
    if (eventText != null) {
      if (!UUID_TEXT.matcher(eventText).matches()) {
        throw new IllegalArgumentException("event_id: must be a lowercase UUID");
      }
      eventId = UUID.fromString(eventText);
    }
    String gte = OrderListRequest.single(params, "created_gte");
    String lt = OrderListRequest.single(params, "created_lt");
    return new WebhookDeliveryListRequest(
        new DeliveryFilter(
            states,
            eventId,
            gte == null ? null : OrderListRequest.time("created_gte", gte),
            lt == null ? null : OrderListRequest.time("created_lt", lt)),
        limit,
        OrderListRequest.single(params, "cursor"));
  }
}
