package io.github.laiwf94.payflow.sim;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;

/**
 * Runs only the simulators, as their own process. Deliberately not a {@code @Configuration}, so the
 * main application's component scan doesn't pick it up.
 */
@EnableAutoConfiguration(
    exclude = {DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class})
@Import({
  SimulatorConfig.class,
  SimulatorErrors.class,
  PaymentSimulatorController.class,
  InventorySimulatorController.class,
  ShippingSimulatorController.class,
  SimulatorControlController.class
})
public class SimulatorApplication {

  public static ConfigurableApplicationContext start(String... args) {
    SpringApplication app = new SpringApplication(SimulatorApplication.class);
    app.setDefaultProperties(java.util.Map.of("spring.application.name", "payflow-simulator"));
    return app.run(args);
  }
}
