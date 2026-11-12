package io.github.laiwf94.payflow.api;

import io.github.laiwf94.payflow.webhook.WebhookEndpoint;
import io.github.laiwf94.payflow.webhook.WebhookEvents;
import io.github.laiwf94.payflow.webhook.WebhookUrls;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * The bodies of {@code POST /v1/webhook_endpoints} and {@code POST /v1/webhook_endpoints/{id}}.
 * Bean Validation checks sizes; the URL's shape, the event types and the description are checked by
 * the webhook service, which owns those rules.
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
}
