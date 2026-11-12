package io.github.laiwf94.payflow.api;

import io.github.laiwf94.payflow.idempotency.IdempotencyFilter;
import io.github.laiwf94.payflow.security.ApiKeyFilter;
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

/**
 * The merchant API for webhook endpoints, scoped to the authenticated merchant. Changes are a POST
 * to the endpoint, as on Stripe, so they go through the same idempotency keys as every other write.
 */
@RestController
@RequestMapping(path = "/v1/webhook_endpoints", produces = MediaType.APPLICATION_JSON_VALUE)
class WebhookEndpointController {

  private final WebhookEndpointService endpoints;

  WebhookEndpointController(WebhookEndpointService endpoints) {
    this.endpoints = endpoints;
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

  /** Deleting twice finds nothing the second time: 404. */
  @DeleteMapping("/{id}")
  Deleted delete(@PathVariable String id, HttpServletRequest http) {
    UUID endpoint = parse(id);
    endpoints.delete(merchant(http), endpoint);
    return new Deleted(endpoint, true);
  }

  record Deleted(UUID id, boolean deleted) {}

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
