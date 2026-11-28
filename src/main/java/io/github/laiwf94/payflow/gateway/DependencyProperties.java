package io.github.laiwf94.payflow.gateway;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Where the payment providers, inventory and carrier live ({@code payflow.dependencies.*}). A blank
 * base URL means the in-process simulator on this server's own port.
 *
 * @param payment the first payment provider, which every tender goes to unless routing says
 *     otherwise
 * @param secondary the second payment provider; with the simulator off and no base URL it is not
 *     configured, and routing to it is refused at startup
 */
@ConfigurationProperties("payflow.dependencies")
public record DependencyProperties(
    @DefaultValue Endpoint payment,
    @DefaultValue Endpoint secondary,
    @DefaultValue Endpoint inventory,
    @DefaultValue Endpoint shipping) {

  /**
   * One dependency's address, credential and timeouts.
   *
   * @param baseUrl absolute http(s) URL including any path prefix; blank for the simulator
   * @param apiKey bearer key; blank uses the in-process simulator's key
   * @param connectTimeout failing to connect within this is a transient failure
   * @param readTimeout no answer within this is an unknown outcome
   */
  public record Endpoint(
      @DefaultValue("") String baseUrl,
      @DefaultValue("") String apiKey,
      @DefaultValue("500ms") Duration connectTimeout,
      @DefaultValue("2s") Duration readTimeout) {}
}
