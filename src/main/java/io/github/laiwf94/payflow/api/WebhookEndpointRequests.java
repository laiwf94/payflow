package io.github.laiwf94.payflow.api;

import io.github.laiwf94.payflow.webhook.DeliveryFilter;
import io.github.laiwf94.payflow.webhook.DeliveryFilter.DeliveryState;
import io.github.laiwf94.payflow.webhook.WebhookEndpoint;
import io.github.laiwf94.payflow.webhook.WebhookEvents;
import io.github.laiwf94.payflow.webhook.WebhookUrls;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The bodies of {@code POST /v1/webhook_endpoints}, {@code POST /v1/webhook_endpoints/{id}}, {@code
 * POST /v1/webhook_endpoints/{id}/rotate_secret} and {@code POST
 * /v1/webhook_endpoints/{id}/replay}. Bean Validation checks sizes; the URL's shape, the event
 * types and the description are checked by the webhook service, which owns those rules.
 */
final class WebhookEndpointRequests {

  private WebhookEndpointRequests() {}

  /** Registers an endpoint. {@code enabled_events} is {@code ["*"]} or a list of event types. */
  record Create(
      @NotNull @Size(min = 1, max = WebhookUrls.MAX_LENGTH) String url,
      @NotNull @Size(min = 1, max = WebhookEvents.MAX_FILTER) List<String> enabledEvents,
      String description) {}

  /** Changes an endpoint: each field given replaces the stored one; an empty description clears. */
  record Update(
      @Size(min = 1, max = WebhookUrls.MAX_LENGTH) String url,
      @Size(min = 1, max = WebhookEvents.MAX_FILTER) List<String> enabledEvents,
      String description,
      WebhookEndpoint.State state) {}

  /**
   * Rotates the signing secret. {@code previous_secret_expires_in} is how many seconds the secret
   * it replaces keeps signing beside the new one: 0 to 604,800 (7 days), a day when absent.
   */
  record RotateSecret(Long previousSecretExpiresIn) {

    java.time.Duration previousTtl() {
      return previousSecretExpiresIn == null
          ? null
          : java.time.Duration.ofSeconds(previousSecretExpiresIn);
    }
  }

  /**
   * Replays deliveries: one event's ({@code event_id}), or those created in a time range ({@code
   * created_gte}, and optionally {@code created_lt}), optionally only those in some states.
   */
  record Replay(UUID eventId, String createdGte, String createdLt, List<DeliveryState> state) {

    DeliveryFilter filter() {
      if (eventId != null && (createdGte != null || createdLt != null)) {
        throw new IllegalArgumentException("give event_id or a time range, not both");
      }
      if (eventId == null && createdGte == null) {
        throw new IllegalArgumentException(
            "give event_id, or created_gte and optionally created_lt, to say what to replay");
      }
      if (state != null && state.contains(DeliveryState.PENDING)) {
        throw new IllegalArgumentException(
            "state: PENDING deliveries are sent anyway; replay SUCCEEDED or FAILED ones");
      }
      return new DeliveryFilter(
          state == null ? Set.of() : Set.copyOf(state),
          eventId,
          createdGte == null ? null : OrderListRequest.time("created_gte", createdGte),
          createdLt == null ? null : OrderListRequest.time("created_lt", createdLt));
    }
  }
}
