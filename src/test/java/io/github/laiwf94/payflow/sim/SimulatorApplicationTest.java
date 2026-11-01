package io.github.laiwf94.payflow.sim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.laiwf94.payflow.support.Api;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import tools.jackson.databind.JsonNode;

class SimulatorApplicationTest {

  @Test
  void runsOnItsOwnWithoutADatabase() {
    try (var ctx = SimulatorApplication.start("--server.port=0", "--payflow.simulator.seed=42")) {
      assertThat(ctx.getBean(PaymentProviderSimulator.class)).isNotNull();
      assertThat(ctx.getBeanNamesForType(javax.sql.DataSource.class)).isEmpty();
      assertThat(ctx.getBean(SimulatorKey.class).value()).startsWith("sim_");
      assertThat(ctx.getBean(SimulatorKey.class)).hasToString("SimulatorKey[redacted]");
      int port = ((WebServerApplicationContext) ctx).getWebServer().getPort();
      JsonNode live = new Api(port, null).get("/livez").json();
      assertThat(live.at("/components/build/details/version").asString())
          .isEqualTo(System.getProperty("payflow.version"));
    }
  }

  @Test
  void shortKeysAreRefused() {
    assertThatThrownBy(() -> SimulatorKey.of("short")).isInstanceOf(IllegalArgumentException.class);
    assertThat(SimulatorKey.of("0123456789abcdef").matches(null)).isFalse();
  }
}
