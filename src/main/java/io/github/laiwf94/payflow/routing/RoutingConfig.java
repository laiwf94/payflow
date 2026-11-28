package io.github.laiwf94.payflow.routing;

import io.github.laiwf94.payflow.gateway.PaymentProviders;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Builds the {@link Router} from {@code payflow.routing.*} against the configured providers. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RoutingProperties.class)
public class RoutingConfig {

  private static final Logger log = LoggerFactory.getLogger(RoutingConfig.class);

  @Bean
  Router router(RoutingProperties props, PaymentProviders providers, MeterRegistry meters) {
    Router r = new Router(props, providers.names(), meters);
    log.info(
        "routing tenders by {} rule(s), otherwise to {}; providers {}",
        r.size(),
        props.defaultProvider(),
        providers.names());
    return r;
  }
}
