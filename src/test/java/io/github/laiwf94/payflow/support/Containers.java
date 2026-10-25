package io.github.laiwf94.payflow.support;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Containers shared by every test in the JVM. Started once, stopped by Testcontainers' reaper. */
public final class Containers {

  public static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:17.10-alpine"))
          .withDatabaseName("payflow")
          .withUsername("payflow")
          .withPassword("payflow");

  static {
    POSTGRES.start();
  }

  private Containers() {}
}
