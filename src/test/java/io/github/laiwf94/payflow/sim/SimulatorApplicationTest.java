package io.github.laiwf94.payflow.sim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SimulatorApplicationTest {

  @Test
  void runsOnItsOwnWithoutADatabase() {
    try (var ctx = SimulatorApplication.start("--server.port=0", "--payflow.simulator.seed=42")) {
      assertThat(ctx.getBean(PaymentProviderSimulator.class)).isNotNull();
      assertThat(ctx.getBeanNamesForType(javax.sql.DataSource.class)).isEmpty();
      assertThat(ctx.getBean(SimulatorKey.class).value()).startsWith("sim_");
      assertThat(ctx.getBean(SimulatorKey.class)).hasToString("SimulatorKey[redacted]");
    }
  }

  @Test
  void shortKeysAreRefused() {
    assertThatThrownBy(() -> SimulatorKey.of("short")).isInstanceOf(IllegalArgumentException.class);
    assertThat(SimulatorKey.of("0123456789abcdef").matches(null)).isFalse();
  }
}
