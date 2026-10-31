package io.github.laiwf94.payflow.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import tools.jackson.databind.ObjectMapper;

/** API key authentication for the merchant API. */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

  /** Runs right after the request id filter, before anything reads the body. */
  public static final int AUTH_ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

  @Bean
  ApiKeys apiKeys(@Value("${payflow.api-keys:}") String spec) {
    return ApiKeys.parse(spec);
  }

  @Bean
  FilterRegistrationBean<ApiKeyFilter> apiKeyFilter(ApiKeys keys, ObjectMapper json) {
    FilterRegistrationBean<ApiKeyFilter> reg =
        new FilterRegistrationBean<>(new ApiKeyFilter(keys, json));
    reg.addUrlPatterns("/v1/*");
    reg.setOrder(AUTH_ORDER);
    return reg;
  }
}
