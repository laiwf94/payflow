package io.github.laiwf94.payflow.api;

import io.github.laiwf94.payflow.security.SecurityConfig;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/** Filters for the merchant API. */
@Configuration(proxyBeanMethods = false)
public class ApiConfig {

  /** Authentication runs first, so an anonymous client can't make us read a large body. */
  @Bean
  FilterRegistrationBean<BodyLimitFilter> bodyLimitFilter(ObjectMapper json) {
    FilterRegistrationBean<BodyLimitFilter> reg =
        new FilterRegistrationBean<>(new BodyLimitFilter(json));
    reg.addUrlPatterns("/v1/*");
    reg.setOrder(SecurityConfig.AUTH_ORDER + 10);
    return reg;
  }
}
