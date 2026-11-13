package io.github.laiwf94.payflow.webhook;

import io.github.laiwf94.payflow.observability.BuildVersion;
import java.security.NoSuchAlgorithmException;
import javax.net.ssl.SSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the webhook sender. */
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
}
