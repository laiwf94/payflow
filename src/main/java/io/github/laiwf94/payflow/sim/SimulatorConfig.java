package io.github.laiwf94.payflow.sim;

import java.time.Clock;
import java.util.SplittableRandom;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/** Wires the two fake payment providers, inventory and carrier when simulators are enabled. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SimulatorProperties.class)
@ConditionalOnProperty(name = "payflow.simulator.enabled", matchIfMissing = true)
public class SimulatorConfig {

  /** The bean name, and {@code @Qualifier}, of the second provider's simulator. */
  public static final String SECONDARY = "secondaryProviderSimulator";

  /** The fault-plan operations of the second provider: {@code secondary.authorize} and so on. */
  public static final String SECONDARY_OPERATIONS = "secondary";

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
    return new SimulatorSettings(props.answerDelay(), props.authorizationLifetime());
  }

  /** The first provider, at {@code /sim/payments}: the one injected wherever none is named. */
  @Bean
  @Primary
  PaymentProviderSimulator paymentProviderSimulator(
      Faults faults, SimulatorSettings settings, ObjectProvider<Clock> clock) {
    return new PaymentProviderSimulator(faults, settings, clock.getIfAvailable(Clock::systemUTC));
  }

  /**
   * The second provider, at {@code /sim/secondary/payments}: its own payments, ledger and {@code
   * secondary.*} fault plans, behind an API of its own ({@link SecondaryProviderController}).
   */
  @Bean(SECONDARY)
  PaymentProviderSimulator secondaryProviderSimulator(
      Faults faults, SimulatorSettings settings, ObjectProvider<Clock> clock) {
    return new PaymentProviderSimulator(
        SECONDARY_OPERATIONS,
        "psp_",
        "psp_rf_",
        faults,
        settings,
        clock.getIfAvailable(Clock::systemUTC));
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
