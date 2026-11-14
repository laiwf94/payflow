package io.github.laiwf94.payflow.api;

import io.github.laiwf94.payflow.webhook.WebhookDeliveryRepository;
import io.github.laiwf94.payflow.webhook.WebhookDeliveryRepository.Attempt;
import io.github.laiwf94.payflow.webhook.WebhookDeliveryRepository.Delivery;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** The delivery log as the API returns it. */
final class WebhookDeliveryResponses {

  private WebhookDeliveryResponses() {}

  /**
   * One attempt: when it started, how long it took, and either the answer's status and the start of
   * its body (at most 1,024 characters) or why there was no answer.
   */
  record AttemptView(
      Instant attemptedAt,
      int latencyMs,
      Integer responseStatus,
      String responseBody,
      String error) {

    static AttemptView of(Attempt a) {
      return a == null
          ? null
          : new AttemptView(a.attemptedAt(), a.latencyMs(), a.status(), a.body(), a.error());
    }
  }

  /** A delivery in the log, with its latest attempt. A ping has no {@code order_id}. */
  record DeliveryView(
      UUID id,
      UUID endpointId,
      UUID eventId,
      String eventType,
      UUID orderId,
      String state,
      int attempts,
      Instant nextAttemptAt,
      Instant createdAt,
      Instant replayedAt,
      AttemptView lastAttempt) {

    static DeliveryView of(Delivery d) {
      return new DeliveryView(
          d.id(),
          d.endpointId(),
          d.eventId(),
          d.eventType(),
          d.orderId(),
          d.state().name(),
          d.attempts(),
          d.nextAttemptAt(),
          d.createdAt(),
          d.replayedAt(),
          AttemptView.of(d.lastAttempt()));
    }
  }

  /** One delivery with the event it carries, exactly as sent, and every attempt, oldest first. */
  record DetailView(
      UUID id,
      UUID endpointId,
      UUID eventId,
      String eventType,
      UUID orderId,
      String state,
      int attempts,
      Instant nextAttemptAt,
      Instant createdAt,
      Instant replayedAt,
      AttemptView lastAttempt,
      JsonNode payload,
      List<AttemptView> attemptLog) {

    static DetailView of(WebhookDeliveryRepository.Detail detail, ObjectMapper json) {
      DeliveryView d = DeliveryView.of(detail.delivery());
      return new DetailView(
          d.id(),
          d.endpointId(),
          d.eventId(),
          d.eventType(),
          d.orderId(),
          d.state(),
          d.attempts(),
          d.nextAttemptAt(),
          d.createdAt(),
          d.replayedAt(),
          d.lastAttempt(),
          json.readTree(detail.payload()),
          detail.attempts().stream().map(AttemptView::of).toList());
    }
  }

  /** A page of the log. {@code next_cursor} is present exactly when {@code has_more} is true. */
  record DeliveryList(List<DeliveryView> data, boolean hasMore, String nextCursor) {}

  /** What a replay did. */
  record ReplayResult(int replayed, int alreadyPending) {}
}
