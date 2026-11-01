package io.github.laiwf94.payflow.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.support.StaticApplicationContext;

@ExtendWith(OutputCaptureExtension.class)
class BuildVersionTest {

  private static ObjectProvider<BuildProperties> provider(BuildProperties properties) {
    StaticApplicationContext ctx = new StaticApplicationContext();
    if (properties != null) {
      ctx.getBeanFactory().registerSingleton("buildProperties", properties);
    }
    return ctx.getBeanProvider(BuildProperties.class);
  }

  private static BuildProperties build(String version) {
    Properties p = new Properties();
    p.setProperty("group", "io.github.laiwf94");
    p.setProperty("artifact", "payflow");
    if (version != null) {
      p.setProperty("version", version);
    }
    return new BuildProperties(p);
  }

  @Test
  void reportsTheBuildVersionAndIsAlwaysUp(CapturedOutput output) {
    BuildVersion v = new BuildVersion(provider(build("1.4.2-rc.1")));
    Health health = v.health();
    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails())
        .containsExactlyEntriesOf(java.util.Map.of("version", "1.4.2-rc.1"));

    v.logVersion();
    assertThat(output.getOut()).contains("payflow 1.4.2-rc.1 ready");
  }

  @Test
  void aBuildWithoutBuildInfoSaysUnknown() {
    assertThat(new BuildVersion(provider(null)).version()).isEqualTo(BuildVersion.UNKNOWN);
    assertThat(new BuildVersion(provider(build(null))).version()).isEqualTo(BuildVersion.UNKNOWN);
  }

  @Test
  void theGradleBuildWritesTheVersionIntoTheClasspath() throws Exception {
    Properties p = new Properties();
    try (var in = getClass().getResourceAsStream("/META-INF/build-info.properties")) {
      assertThat(in).as("build-info.properties on the classpath").isNotNull();
      p.load(in);
    }
    assertThat(p.getProperty("build.version")).isEqualTo(System.getProperty("payflow.version"));
    // No build time: the same source and version give the same jar.
    assertThat(p.getProperty("build.time")).isNull();
  }
}
