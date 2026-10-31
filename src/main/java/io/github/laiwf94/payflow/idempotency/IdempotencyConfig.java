package io.github.laiwf94.payflow.idempotency;

import io.github.laiwf94.payflow.security.SecurityConfig;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Idempotency keys on the merchant API, after authentication and the body limit. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IdempotencyProperties.class)
public class IdempotencyConfig {

  @Bean
  FilterRegistrationBean<IdempotencyFilter> idempotencyFilter(
      IdempotencyStore store, TransactionTemplate tx, ObjectMapper json, MeterRegistry meters) {
    FilterRegistrationBean<IdempotencyFilter> reg =
        new FilterRegistrationBean<>(new IdempotencyFilter(store, tx, json, meters));
    reg.addUrlPatterns("/v1/*");
    reg.setOrder(SecurityConfig.AUTH_ORDER + 20);
    return reg;
  }
}
