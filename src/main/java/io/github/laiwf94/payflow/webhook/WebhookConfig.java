package io.github.laiwf94.payflow.webhook;

import io.github.laiwf94.payflow.observability.BuildVersion;
import io.github.laiwf94.payflow.outbox.RelayListener;
import io.github.laiwf94.payflow.saga.Backoff;
import io.micrometer.core.instrument.MeterRegistry;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.Random;
import javax.net.ssl.SSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Wires the webhook sender, the dispatcher, and the relay's hand-off to it. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WebhookProperties.class)
public class WebhookConfig {

  private static final Logger log = LoggerFactory.getLogger(WebhookConfig.class);

  @Bean
  SafeHttpSender webhookSender(WebhookProperties props, BuildVersion version)
      throws NoSuchAlgorithmException {
    AddressPolicy policy = AddressPolicy.allowing(props.allowedNetworks());
    if (!policy.allowed().isEmpty()) {
      log.atWarn()
          .addKeyValue("networks", policy.allowed().toString())
          .log("webhooks may be sent to {}, which are not public", policy.allowed());
    }
    return new SafeHttpSender(
        policy,
        SafeHttpSender.Resolver.SYSTEM,
        SSLContext.getDefault(),
        new SafeHttpSender.Options(
            props.connectTimeout(), props.timeout(), props.maxResponseBody()),
        "payflow-webhooks/" + version.version());
  }

  @Bean
  WebhookDispatcher webhookDispatcher(
      WebhookDeliveryRepository deliveries,
      WebhookEndpointService endpoints,
      SafeHttpSender sender,
      WebhookProperties props,
      Clock clock,
      MeterRegistry meters) {
    return new WebhookDispatcher(
        deliveries,
        endpoints,
        sender,
        props,
        clock,
        new Backoff(props.retryBase(), props.retryCap(), new Random()),
        meters);
  }

  /**
   * Creates each relayed event's deliveries in the relay's own transaction, and wakes the
   * dispatcher once they commit.
   */
  @Bean
  RelayListener webhookFanout(WebhookDeliveryRepository deliveries, WebhookDispatcher dispatcher) {
    return (ids, at) -> {
      if (deliveries.enqueue(ids, at) > 0) {
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
              @Override
              public void afterCommit() {
                dispatcher.wakeUp();
              }
            });
      }
    };
  }
}
