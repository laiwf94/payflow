package io.github.laiwf94.payflow.support;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.redpanda.RedpandaContainer;
import org.testcontainers.utility.DockerImageName;

/** Containers shared by every test in the JVM. Started once, stopped by Testcontainers' reaper. */
public final class Containers {

  public static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:17.10-alpine"))
          .withDatabaseName("payflow")
          .withUsername("payflow")
          .withPassword("payflow");

  public static final RedpandaContainer REDPANDA =
      new RedpandaContainer("docker.redpanda.com/redpandadata/redpanda:v25.2.9");

  static {
    POSTGRES.start();
    REDPANDA.start();
  }

  private Containers() {}
}
