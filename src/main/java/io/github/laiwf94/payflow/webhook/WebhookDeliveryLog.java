package io.github.laiwf94.payflow.webhook;

import io.github.laiwf94.payflow.webhook.WebhookDeliveryRepository.Delivery;
import io.github.laiwf94.payflow.webhook.WebhookDeliveryRepository.Detail;
import io.github.laiwf94.payflow.webhook.WebhookDeliveryRepository.Replayed;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/**
 * What a merchant sees and does with an endpoint's deliveries: the log of every delivery and each
 * of its attempts, replaying deliveries by event or by time range, and test pings. Every call is
 * scoped to one of the merchant's live endpoints; another merchant's endpoint, or a deleted one, is
 * {@link WebhookEndpointService.NotFound}.
 */
@Service
public class WebhookDeliveryLog {

  /** Deliveries one replay may make due again; a wider range is refused, not cut short. */
  public static final int MAX_REPLAY = 1000;

  /** No such delivery to this endpoint. */
  public static final class DeliveryNotFound extends RuntimeException {
    private static final long serialVersionUID = 1L;

    DeliveryNotFound(String message) {
      super(message);
    }
  }

  /** The answer for a delivery the endpoint doesn't have. */
  public static DeliveryNotFound deliveryNotFound() {
    return new DeliveryNotFound("webhook delivery not found");
  }

  /** One page of the log, newest first; {@code hasMore} when another page follows it. */
  public record Page(List<Delivery> deliveries, boolean hasMore) {}

  private final WebhookEndpointService endpoints;
  private final WebhookDeliveryRepository deliveries;
  private final WebhookDispatcher dispatcher;
  private final ObjectMapper json;
  private final Clock clock;

  public WebhookDeliveryLog(
      WebhookEndpointService endpoints,
      WebhookDeliveryRepository deliveries,
      WebhookDispatcher dispatcher,
      ObjectMapper json,
      Clock clock) {
    this.endpoints = endpoints;
    this.deliveries = deliveries;
    this.dispatcher = dispatcher;
    this.json = json;
    this.clock = clock;
  }

  /**
   * Up to {@code limit} of the endpoint's deliveries that match {@code filter}, newest first, after
   * the position {@code (afterCreatedAt, afterId)} when one is given.
   *
   * @throws WebhookEndpointService.NotFound when the merchant has no such endpoint
   */
  @Transactional(readOnly = true)
  public Page list(
      String merchant,
      UUID endpointId,
      DeliveryFilter filter,
      Instant afterCreatedAt,
      UUID afterId,
      int limit) {
    endpoints.get(merchant, endpointId);
    List<Delivery> rows = deliveries.list(endpointId, filter, afterCreatedAt, afterId, limit + 1);
    boolean more = rows.size() > limit;
    return new Page(more ? rows.subList(0, limit) : rows, more);
  }

  /**
   * One delivery, with the event it carries and every attempt.
   *
   * @throws WebhookEndpointService.NotFound when the merchant has no such endpoint
   * @throws DeliveryNotFound when the endpoint has no such delivery
   */
  @Transactional(readOnly = true)
  public Detail get(String merchant, UUID endpointId, UUID deliveryId) {
    endpoints.get(merchant, endpointId);
    return deliveries
        .find(endpointId, deliveryId)
        .orElseThrow(WebhookDeliveryLog::deliveryNotFound);
  }

  /**
   * Sends the endpoint's settled deliveries that match {@code filter} again, each with a new retry
   * window. A filter names one event ({@code eventId}) or a time range with at least {@code
   * createdGte}. Deliveries still pending are left as they are and counted, since they will be sent
   * anyway. A disabled endpoint's replayed deliveries wait until it is enabled.
   *
   * @throws IllegalArgumentException when the filter is neither an event nor a range, or more than
   *     {@value #MAX_REPLAY} deliveries match
   * @throws WebhookEndpointService.NotFound when the merchant has no such endpoint
   * @throws DeliveryNotFound when {@code eventId} was never delivered to this endpoint
   */
  @Transactional(
      noRollbackFor = {
        IllegalArgumentException.class,
        WebhookEndpointService.NotFound.class,
        DeliveryNotFound.class
      })
  public Replayed replay(String merchant, UUID endpointId, DeliveryFilter filter) {
    if (filter.eventId() == null && filter.createdGte() == null) {
      throw new IllegalArgumentException(
          "give event_id, or created_gte and optionally created_lt, to say what to replay");
    }
    endpoints.get(merchant, endpointId);
    Replayed r = deliveries.replay(endpointId, filter, MAX_REPLAY, clock.instant());
    if (filter.eventId() != null && r.replayed() == 0 && r.alreadyPending() == 0) {
      throw new DeliveryNotFound(
          "event " + filter.eventId() + " has no delivery to this endpoint that matches");
    }
    if (r.replayed() > 0) {
      wakeDispatcherAfterCommit();
    }
    return r;
  }

  /**
   * Queues a test ping to the endpoint, whatever event types it subscribes to. The dispatcher sends
   * it once, signed like any event, and the answer appears in the delivery log. Returns the ping's
   * delivery, pending.
   *
   * @throws IllegalArgumentException when the endpoint is disabled
   * @throws WebhookEndpointService.NotFound when the merchant has no such endpoint
   */
  @Transactional(
      noRollbackFor = {IllegalArgumentException.class, WebhookEndpointService.NotFound.class})
  public Delivery ping(String merchant, UUID endpointId) {
    WebhookEndpoint e = endpoints.get(merchant, endpointId);
    if (e.state() != WebhookEndpoint.State.ENABLED) {
      throw new IllegalArgumentException(
          "the endpoint is disabled and receives nothing, pings included; enable it first");
    }
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    UUID eventId = UUID.randomUUID();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("event_id", eventId);
    payload.put("event_type", WebhookEvents.PING);
    payload.put("merchant_id", merchant);
    payload.put("endpoint_id", endpointId);
    payload.put("created_at", now.toString());
    UUID id = deliveries.enqueuePing(endpointId, eventId, json.writeValueAsString(payload), now);
    wakeDispatcherAfterCommit();
    return deliveries.find(endpointId, id).orElseThrow().delivery();
  }

  private void wakeDispatcherAfterCommit() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              dispatcher.wakeUp();
            }
          });
    } else {
      dispatcher.wakeUp();
    }
  }
}
