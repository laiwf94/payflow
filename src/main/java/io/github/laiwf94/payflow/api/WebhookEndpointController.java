package io.github.laiwf94.payflow.api;

import io.github.laiwf94.payflow.api.WebhookDeliveryResponses.DeliveryList;
import io.github.laiwf94.payflow.api.WebhookDeliveryResponses.DeliveryView;
import io.github.laiwf94.payflow.api.WebhookDeliveryResponses.DetailView;
import io.github.laiwf94.payflow.api.WebhookDeliveryResponses.ReplayResult;
import io.github.laiwf94.payflow.idempotency.IdempotencyFilter;
import io.github.laiwf94.payflow.security.ApiKeyFilter;
import io.github.laiwf94.payflow.webhook.WebhookDeliveryLog;
import io.github.laiwf94.payflow.webhook.WebhookDeliveryRepository;
import io.github.laiwf94.payflow.webhook.WebhookEndpointService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/**
 * The merchant API for webhook endpoints, scoped to the authenticated merchant. Changes are a POST
 * to the endpoint, as on Stripe, so they go through the same idempotency keys as every other write.
 */
@RestController
@RequestMapping(path = "/v1/webhook_endpoints", produces = MediaType.APPLICATION_JSON_VALUE)
class WebhookEndpointController {

  private final WebhookEndpointService endpoints;
  private final WebhookDeliveryLog log;
  private final ListCursors cursors;
  private final ObjectMapper json;

  WebhookEndpointController(
      WebhookEndpointService endpoints,
      WebhookDeliveryLog log,
      ListCursors cursors,
      ObjectMapper json) {
    this.endpoints = endpoints;
    this.log = log;
    this.cursors = cursors;
    this.json = json;
  }

  /** Registers an endpoint. The answer carries its signing secret, which is never shown again. */
  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<WebhookEndpointResponse> create(
      @Valid @RequestBody WebhookEndpointRequests.Create req, HttpServletRequest http) {
    WebhookEndpointService.Created c =
        endpoints.create(merchant(http), req.url(), req.enabledEvents(), req.description());
    // The answer is the one place the secret is shown; its stored copy for replays is sealed.
    IdempotencyFilter.sealStoredAnswer(http);
    return ResponseEntity.created(URI.create("/v1/webhook_endpoints/" + c.endpoint().id()))
        .body(WebhookEndpointResponse.of(c.endpoint(), c.secret()));
  }

  /** The merchant's endpoints, oldest first. There are at most a few, so there are no pages. */
  @GetMapping
  Map<String, List<WebhookEndpointResponse>> list(HttpServletRequest http) {
    return Map.of(
        "data", endpoints.list(merchant(http)).stream().map(WebhookEndpointResponse::of).toList());
  }

  @GetMapping("/{id}")
  WebhookEndpointResponse get(@PathVariable String id, HttpServletRequest http) {
    return WebhookEndpointResponse.of(endpoints.get(merchant(http), parse(id)));
  }

  @PostMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
  WebhookEndpointResponse update(
      @PathVariable String id,
      @Valid @RequestBody WebhookEndpointRequests.Update req,
      HttpServletRequest http) {
    return WebhookEndpointResponse.of(
        endpoints.update(
            merchant(http),
            parse(id),
            new WebhookEndpointService.Changes(
                req.url(), req.enabledEvents(), req.description(), req.state())));
  }

  /**
   * Gives the endpoint a new signing secret, shown in this answer only. The secret it replaces
   * keeps signing beside it for a while, so deliveries carry both signatures as the merchant
   * deploys the new one. The body is optional.
   */
  @PostMapping("/{id}/rotate_secret")
  WebhookEndpointResponse rotateSecret(
      @PathVariable String id,
      @RequestBody(required = false) WebhookEndpointRequests.RotateSecret req,
      HttpServletRequest http) {
    WebhookEndpointService.Rotated r =
        endpoints.rotateSecret(merchant(http), parse(id), req == null ? null : req.previousTtl());
    // As on creation: the answer shows the secret, so its stored copy for replays is sealed.
    IdempotencyFilter.sealStoredAnswer(http);
    return WebhookEndpointResponse.of(r.endpoint(), r.secret());
  }

  /** Deleting twice finds nothing the second time: 404. */
  @DeleteMapping("/{id}")
  Deleted delete(@PathVariable String id, HttpServletRequest http) {
    UUID endpoint = parse(id);
    endpoints.delete(merchant(http), endpoint);
    return new Deleted(endpoint, true);
  }

  record Deleted(UUID id, boolean deleted) {}

  /**
   * The endpoint's deliveries, newest first, a page at a time, each with its latest attempt. The
   * cursor is signed over the merchant, the endpoint and the filters.
   */
  @GetMapping("/{id}/deliveries")
  DeliveryList deliveries(@PathVariable String id, HttpServletRequest http) {
    String merchant = merchant(http);
    UUID endpoint = parse(id);
    WebhookDeliveryListRequest req = WebhookDeliveryListRequest.parse(http.getParameterMap());
    String scope = endpoint + "\n" + req.filter().canonical();
    ListCursors.Position after =
        req.cursor() == null
            ? null
            : cursors.decode(ListCursors.Listing.WEBHOOK_DELIVERIES, merchant, scope, req.cursor());
    WebhookDeliveryLog.Page page =
        log.list(
            merchant,
            endpoint,
            req.filter(),
            after == null ? null : after.createdAt(),
            after == null ? null : after.id(),
            req.limit());
    String next = null;
    if (page.hasMore()) {
      WebhookDeliveryRepository.Delivery last = page.deliveries().getLast();
      next =
          cursors.encode(
              ListCursors.Listing.WEBHOOK_DELIVERIES,
              merchant,
              scope,
              new ListCursors.Position(last.createdAt(), last.id()));
    }
    return new DeliveryList(
        page.deliveries().stream().map(DeliveryView::of).toList(), page.hasMore(), next);
  }

  /** One delivery, with the event exactly as sent and every attempt, oldest first. */
  @GetMapping("/{id}/deliveries/{delivery_id}")
  DetailView delivery(
      @PathVariable String id,
      @PathVariable("delivery_id") String deliveryId,
      HttpServletRequest http) {
    UUID delivery;
    try {
      delivery = parse(deliveryId);
    } catch (WebhookEndpointService.NotFound e) {
      // The endpoint is checked first, so a malformed delivery id on a missing endpoint still
      // says the endpoint is missing.
      endpoints.get(merchant(http), parse(id));
      throw WebhookDeliveryLog.deliveryNotFound();
    }
    return DetailView.of(log.get(merchant(http), parse(id), delivery), json);
  }

  /** Sends settled deliveries again: one event's, or those in a time range. */
  @PostMapping(path = "/{id}/replay", consumes = MediaType.APPLICATION_JSON_VALUE)
  ReplayResult replay(
      @PathVariable String id,
      @RequestBody WebhookEndpointRequests.Replay req,
      HttpServletRequest http) {
    WebhookDeliveryRepository.Replayed r = log.replay(merchant(http), parse(id), req.filter());
    return new ReplayResult(r.replayed(), r.alreadyPending());
  }

  /**
   * Queues a signed test ping to the endpoint. It is sent in the background, once; the answer is in
   * the delivery the response points to.
   */
  @PostMapping("/{id}/ping")
  ResponseEntity<DeliveryView> ping(@PathVariable String id, HttpServletRequest http) {
    UUID endpoint = parse(id);
    WebhookDeliveryRepository.Delivery d = log.ping(merchant(http), endpoint);
    return ResponseEntity.accepted()
        .location(URI.create("/v1/webhook_endpoints/" + endpoint + "/deliveries/" + d.id()))
        .body(DeliveryView.of(d));
  }

  private static String merchant(HttpServletRequest http) {
    return (String) http.getAttribute(ApiKeyFilter.MERCHANT_ATTRIBUTE);
  }

  /** A malformed id can't name an endpoint, so it is simply not found. */
  private static UUID parse(String id) {
    try {
      if (id.length() == 36) {
        return UUID.fromString(id);
      }
    } catch (IllegalArgumentException e) {
      // fall through
    }
    throw new WebhookEndpointService.NotFound();
  }
}
