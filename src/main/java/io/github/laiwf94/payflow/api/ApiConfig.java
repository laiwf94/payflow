package io.github.laiwf94.payflow.api;

import io.github.laiwf94.payflow.security.SecurityConfig;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;

/** Filters and JSON strictness for the merchant API. */
@Configuration(proxyBeanMethods = false)
public class ApiConfig {

  /**
   * A number or boolean where a string belongs is a 400, like a string where a number belongs.
   * Jackson would otherwise turn {@code "sku": 123} into {@code "123"}. The tolerant mappers for
   * provider responses undo this with {@link #lenientText}.
   */
  @Bean
  JsonMapperBuilderCustomizer strictText() {
    return builder ->
        builder.withCoercionConfig(
            LogicalType.Textual,
            c ->
                c.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail));
  }

  /** Restores Jackson's default number and boolean to string coercion on a rebuilt mapper. */
  public static ObjectMapper lenientText(ObjectMapper strict) {
    return strict
        .rebuild()
        .withCoercionConfig(
            LogicalType.Textual,
            c ->
                c.setCoercion(CoercionInputShape.Integer, CoercionAction.TryConvert)
                    .setCoercion(CoercionInputShape.Float, CoercionAction.TryConvert)
                    .setCoercion(CoercionInputShape.Boolean, CoercionAction.TryConvert))
        .build();
  }

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
