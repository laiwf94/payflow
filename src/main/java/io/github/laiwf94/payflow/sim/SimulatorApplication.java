package io.github.laiwf94.payflow.sim;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;

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
    app.setDefaultProperties(Map.of("spring.application.name", "payflow-simulator"));
    // These override the shared application.yml, which is written for the main service.
    app.addInitializers(
        ctx ->
            ctx.getEnvironment()
                .getPropertySources()
                .addFirst(
                    new MapPropertySource(
                        "simulator",
                        Map.of(
                            // No database here, so readiness is just "started".
                            "management.endpoint.health.group.readiness.include",
                            "readinessState",
                            // Health is on the main port (/livez, /readyz); keep the management
                            // port out of the way of a payflow process on the same host.
                            "management.server.port",
                            "0"))));
    return app.run(args);
  }
}
