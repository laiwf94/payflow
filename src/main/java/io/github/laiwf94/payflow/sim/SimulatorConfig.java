package io.github.laiwf94.payflow.sim;

import java.util.SplittableRandom;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the fake payment provider, inventory and carrier when simulators are enabled. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SimulatorProperties.class)
@ConditionalOnProperty(name = "payflow.simulator.enabled", matchIfMissing = true)
public class SimulatorConfig {

  @Bean
  SimulatorKey simulatorKey(SimulatorProperties props) {
    return SimulatorKey.of(props.key());
  }

  @Bean
  Faults simulatorFaults(SimulatorProperties props) {
    return new Faults(
        props.seed() == null ? new SplittableRandom() : new SplittableRandom(props.seed()));
  }

  @Bean
  SimulatorSettings simulatorSettings(SimulatorProperties props) {
    return new SimulatorSettings(props.answerDelay());
  }

  @Bean
  PaymentProviderSimulator paymentProviderSimulator(Faults faults, SimulatorSettings settings) {
    return new PaymentProviderSimulator(faults, settings);
  }

  @Bean
  InventorySimulator inventorySimulator(Faults faults, SimulatorProperties props) {
    return new InventorySimulator(faults, props.defaultStock());
  }

  @Bean
  ShippingSimulator shippingSimulator(Faults faults) {
    return new ShippingSimulator(faults);
  }

  @Bean
  SimulatedResponses simulatedResponses(SimulatorSettings settings) {
    return new SimulatedResponses(settings);
  }

  @Bean
  FilterRegistrationBean<SimulatorAuthFilter> simulatorAuthFilter(SimulatorKey key) {
    FilterRegistrationBean<SimulatorAuthFilter> reg =
        new FilterRegistrationBean<>(new SimulatorAuthFilter(key));
    reg.addUrlPatterns("/sim/*");
    return reg;
  }
}
