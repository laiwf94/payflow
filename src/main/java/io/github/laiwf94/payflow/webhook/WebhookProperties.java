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
 */
@ConfigurationProperties("payflow.webhooks")
public record WebhookProperties(
    @DefaultValue({}) List<String> allowedNetworks,
    @DefaultValue("3s") Duration connectTimeout,
    @DefaultValue("10s") Duration timeout,
    @DefaultValue("1024") int maxResponseBody) {

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
  }
}
