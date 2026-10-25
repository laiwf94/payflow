package io.github.laiwf94.payflow.support;

import java.util.Map;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

/**
 * Points every Spring context started in tests at the shared containers. Registered in {@code
 * META-INF/spring.factories}, so it runs before any bean exists.
 *
 * <p>Not {@code @ServiceConnection} beans: a container bean belongs to its context, and closing one
 * context (after {@code @DirtiesContext}) would stop the containers every other cached context is
 * still using.
 */
public class ContainersInitializer
    implements ApplicationContextInitializer<ConfigurableApplicationContext> {

  @Override
  public void initialize(ConfigurableApplicationContext ctx) {
    ctx.getEnvironment()
        .getPropertySources()
        .addFirst(
            new MapPropertySource(
                "testContainers",
                Map.of(
                    "spring.datasource.url", Containers.POSTGRES.getJdbcUrl(),
                    "spring.datasource.username", Containers.POSTGRES.getUsername(),
                    "spring.datasource.password", Containers.POSTGRES.getPassword(),
                    "spring.kafka.bootstrap-servers", Containers.REDPANDA.getBootstrapServers())));
  }
}
