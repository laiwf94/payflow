package io.github.laiwf94.payflow.webhook;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Webhook sending settings ({@code payflow.webhooks.*}).
 *
 * @param allowedNetworks networks webhooks may be sent to although {@link AddressPolicy} refuses
 *     them, in CIDR form ({@code 172.18.0.0/16}, {@code ::1/128}); for a development stack whose
 *     merchant server is on a private network. Empty, the default, in production.
 * @param connectTimeout time a connection to one of the host's addresses may take
 * @param timeout time one delivery attempt may take, from the lookup to the last byte read
 * @param maxResponseBody bytes of the merchant's answer kept for the delivery log
 * @param enabled run the background dispatcher
 * @param pollInterval idle wait between polls when the relay hasn't woken the dispatcher
 * @param batchSize deliveries claimed per poll
 * @param concurrency deliveries this instance sends at once
 * @param perEndpoint deliveries to one endpoint this instance sends at once, so one slow endpoint
 *     can't take every slot
 * @param lease how long a claimed delivery is held before another instance may try it; longer than
 *     {@code timeout}, so only an instance that died gives one up
 * @param retryBase the first retry's backoff ceiling; it doubles per attempt (full jitter)
 * @param retryCap the most the backoff ceiling grows to
 * @param retryWindow how long after it was created a delivery is retried before it is given up
 * @param shutdownGrace how long a stop waits for attempts in flight before handing them back
 * @param disableAfter how long an endpoint may fail every attempt before payflow disables it and
 *     sends it a final notice; it is also disabled when a delivery fails every attempt of its retry
 *     window, if that window is at least this long
 */
@ConfigurationProperties("payflow.webhooks")
public record WebhookProperties(
    @DefaultValue({}) List<String> allowedNetworks,
    @DefaultValue("3s") Duration connectTimeout,
    @DefaultValue("10s") Duration timeout,
    @DefaultValue("1024") int maxResponseBody,
    @DefaultValue("true") boolean enabled,
    @DefaultValue("500ms") Duration pollInterval,
    @DefaultValue("16") int batchSize,
    @DefaultValue("64") int concurrency,
    @DefaultValue("8") int perEndpoint,
    @DefaultValue("60s") Duration lease,
    @DefaultValue("5s") Duration retryBase,
    @DefaultValue("1h") Duration retryCap,
    @DefaultValue("3d") Duration retryWindow,
    @DefaultValue("3s") Duration shutdownGrace,
    @DefaultValue("3d") Duration disableAfter) {

  public WebhookProperties {
    allowedNetworks = List.copyOf(allowedNetworks);
    if (timeout.compareTo(Duration.ofSeconds(60)) > 0 || connectTimeout.compareTo(timeout) > 0) {
      throw new IllegalArgumentException(
          "webhook timeout must be at most 60 s and no shorter than the connect timeout");
    }
    if (maxResponseBody < 0 || maxResponseBody > 1024) {
      throw new IllegalArgumentException(
          "max response body must be 0-1024 bytes, what the delivery log keeps");
    }
    if (batchSize < 1 || batchSize > 500 || concurrency < 1 || concurrency > 1024) {
      throw new IllegalArgumentException("webhook batch size must be 1-500 and concurrency 1-1024");
    }
    if (perEndpoint < 1 || perEndpoint > concurrency) {
      throw new IllegalArgumentException("per-endpoint concurrency must be 1-" + concurrency);
    }
    if (lease.compareTo(timeout.plusSeconds(5)) < 0) {
      throw new IllegalArgumentException(
          "webhook lease must be at least the timeout plus 5 s, or a slow attempt loses it");
    }
    if (pollInterval.isNegative() || pollInterval.isZero()) {
      throw new IllegalArgumentException("webhook poll interval must be positive");
    }
    if (retryBase.isNegative()
        || retryBase.isZero()
        || retryCap.compareTo(retryBase) < 0
        || retryWindow.compareTo(retryCap) < 0) {
      throw new IllegalArgumentException("webhook retries need 0 < base <= cap <= window");
    }
    if (shutdownGrace.isNegative() || shutdownGrace.compareTo(Duration.ofMinutes(1)) > 0) {
      throw new IllegalArgumentException("webhook shutdown grace must be 0-60 s");
    }
    if (disableAfter.compareTo(Duration.ofHours(1)) < 0
        || disableAfter.compareTo(Duration.ofDays(30)) > 0) {
      throw new IllegalArgumentException(
          "webhook disable-after must be 1 h to 30 d, so a brief outage never disables an endpoint");
    }
  }
}
