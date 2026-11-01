package io.github.laiwf94.payflow.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The running version, from {@code META-INF/build-info.properties}: a release build takes it from
 * the git tag. It is the {@code build} component of {@code /livez}, so an operator can see which
 * version each instance runs during a rolling deploy, and the first line logged once the service is
 * ready.
 */
@Component("build")
public class BuildVersion implements HealthIndicator {

  /** What a build without build-info (an IDE run of the classes) reports. */
  static final String UNKNOWN = "unknown";

  private static final Logger log = LoggerFactory.getLogger(BuildVersion.class);

  private final String version;

  public BuildVersion(ObjectProvider<BuildProperties> build) {
    BuildProperties properties = build.getIfAvailable();
    this.version =
        properties == null || properties.getVersion() == null ? UNKNOWN : properties.getVersion();
  }

  public String version() {
    return version;
  }

  /** Always up: liveness must not depend on anything, this only carries the version. */
  @Override
  public Health health() {
    return Health.up().withDetail("version", version).build();
  }

  @EventListener(ApplicationReadyEvent.class)
  void logVersion() {
    log.atInfo().addKeyValue("version", version).log("payflow {} ready", version);
  }
}
