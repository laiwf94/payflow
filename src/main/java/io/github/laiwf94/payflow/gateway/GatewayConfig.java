package io.github.laiwf94.payflow.gateway;

import io.github.laiwf94.payflow.sim.SimulatorKey;
import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

/** HTTP clients for the payment provider, inventory and carrier. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DependencyProperties.class)
public class GatewayConfig {

  /** This server's port, for reaching the in-process simulators. */
  static final class LocalPort implements ApplicationListener<WebServerInitializedEvent> {
    private final AtomicInteger port = new AtomicInteger();

    @Override
    public void onApplicationEvent(WebServerInitializedEvent event) {
      if (event.getApplicationContext().getServerNamespace() == null) {
        port.set(event.getWebServer().getPort());
      }
    }

    int get() {
      int p = port.get();
      if (p <= 0) {
        throw new IllegalStateException("in-process simulator needs the web server to be running");
      }
      return p;
    }
  }

  @Bean
  LocalPort gatewayLocalPort() {
    return new LocalPort();
  }

  @Bean
  PaymentGateway paymentGateway(
      DependencyProperties props,
      LocalPort port,
      ObjectProvider<SimulatorKey> simKey,
      RestClient.Builder builder,
      ObjectMapper json) {
    return new HttpPaymentGateway(
        dependency("payment", "/sim/payments", props.payment(), port, simKey, builder, json));
  }

  @Bean
  InventoryService inventoryService(
      DependencyProperties props,
      LocalPort port,
      ObjectProvider<SimulatorKey> simKey,
      RestClient.Builder builder,
      ObjectMapper json) {
    return new HttpInventoryService(
        dependency(
            "inventory",
            "/sim/inventory/reservations",
            props.inventory(),
            port,
            simKey,
            builder,
            json));
  }

  @Bean
  ShippingService shippingService(
      DependencyProperties props,
      LocalPort port,
      ObjectProvider<SimulatorKey> simKey,
      RestClient.Builder builder,
      ObjectMapper json) {
    return new HttpShippingService(
        dependency(
            "shipping", "/sim/shipping/shipments", props.shipping(), port, simKey, builder, json));
  }

  static HttpDependency dependency(
      String name,
      String simulatorPath,
      DependencyProperties.Endpoint ep,
      LocalPort port,
      ObjectProvider<SimulatorKey> simKey,
      RestClient.Builder builder,
      ObjectMapper json) {
    SimulatorKey sk = simKey.getIfAvailable();
    Supplier<String> base;
    if (ep.baseUrl().isBlank()) {
      if (sk == null) {
        throw new IllegalStateException(
            "payflow.dependencies." + name + ".base-url is required when the simulator is off");
      }
      base = () -> "http://127.0.0.1:" + port.get() + simulatorPath;
    } else {
      String url = validated(name, ep.baseUrl());
      base = () -> url;
    }
    Supplier<String> key;
    if (!ep.apiKey().isBlank()) {
      String k = ep.apiKey();
      key = () -> k;
    } else if (sk != null) {
      key = sk::value;
    } else {
      throw new IllegalStateException("payflow.dependencies." + name + ".api-key is required");
    }
    // Providers add response fields over time; reading them strictly would turn a harmless
    // addition into an outage.
    ObjectMapper tolerant =
        json.rebuild().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    return new HttpDependency(
        name, base, key, ep.connectTimeout(), ep.readTimeout(), builder, tolerant);
  }

  /**
   * The base URL is where we send a bearer credential, so it must be a plain http(s) URL from
   * configuration: no user info, query or fragment that could redirect or leak it.
   */
  static String validated(String name, String url) {
    URI u;
    try {
      u = URI.create(url);
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException(name + " base URL is not a valid URI", e);
    }
    boolean ok =
        ("http".equals(u.getScheme()) || "https".equals(u.getScheme()))
            && u.getHost() != null
            && u.getUserInfo() == null
            && u.getQuery() == null
            && u.getFragment() == null;
    if (!ok) {
      throw new IllegalStateException(
          name + " base URL must be http(s)://host[:port][/path] without credentials or query");
    }
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }
}
