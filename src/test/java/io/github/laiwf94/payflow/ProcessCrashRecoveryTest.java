package io.github.laiwf94.payflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.laiwf94.payflow.sim.FaultPlan;
import io.github.laiwf94.payflow.sim.Faults;
import io.github.laiwf94.payflow.sim.PaymentProviderSimulator;
import io.github.laiwf94.payflow.sim.ShippingSimulator;
import io.github.laiwf94.payflow.sim.SimulatorApplication;
import io.github.laiwf94.payflow.support.Api;
import io.github.laiwf94.payflow.support.Containers;
import io.github.laiwf94.payflow.support.TestKeys;
import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Runs the packaged jar as a child process and kills it with SIGKILL twice: once while the payment
 * authorization is in flight, once after the provider has captured but before the service heard
 * back. The simulators run in this JVM, so the provider's ledger survives the kills, exactly as a
 * real provider's would.
 */
class ProcessCrashRecoveryTest {

  private static final String SIM_KEY = "crash-test-simulator-key-0123";

  private ConfigurableApplicationContext simulators;
  private Process app;
  private int appPort;
  private Path log;

  @BeforeEach
  void startSimulators() {
    simulators =
        SimulatorApplication.start("--server.port=0", "--payflow.simulator.key=" + SIM_KEY);
  }

  @AfterEach
  void stopEverything() {
    if (app != null) {
      app.destroyForcibly();
    }
    simulators.close();
  }

  @Test
  void anOrderSurvivesTwoKillsWithoutBeingChargedTwice() throws Exception {
    Faults faults = simulators.getBean(Faults.class);
    PaymentProviderSimulator payments = simulators.getBean(PaymentProviderSimulator.class);
    faults.reset(new SplittableRandom(1));
    faults.set("payment.authorize", new FaultPlan(0, 0, 0, Duration.ofSeconds(4), List.of()));
    faults.set("payment.capture", new FaultPlan(0, 0, 0, Duration.ofSeconds(30), List.of()));

    appPort = freePort();
    log = Path.of("build", "crash-test-" + UUID.randomUUID() + ".log");
    startApp();
    Api api = new Api(appPort, TestKeys.ACME);
    String id = api.post("/v1/orders", Api.orderBody()).json().get("id").asString();

    // First kill: inventory is reserved and the authorization call is waiting on the provider.
    await().atMost(Duration.ofSeconds(20)).until(() -> "INVENTORY_RESERVED".equals(state(id)));
    killApp();

    startApp();
    // After the lease expires the new process resumes, finds the authorization the dead one made,
    // and moves on to capture, which the provider applies and then sits on.
    await()
        .atMost(Duration.ofSeconds(30))
        .until(
            () -> payments.ledgerFor(id).stream().anyMatch(e -> e.operation().equals("capture")));
    assertThat(state(id)).isEqualTo("PAYMENT_AUTHORIZED");
    killApp();

    faults.set("payment.capture", FaultPlan.NONE);
    faults.set("payment.authorize", FaultPlan.NONE);
    startApp();
    await().atMost(Duration.ofSeconds(30)).until(() -> "COMPLETED".equals(state(id)));

    assertThat(payments.ledgerFor(id))
        .extracting(PaymentProviderSimulator.LedgerEntry::operation)
        .containsExactly("authorize", "capture");
    assertThat(simulators.getBean(ShippingSimulator.class).shipment(id)).isPresent();
    // The killed processes' consumers hold their partitions until their sessions time out.
    await()
        .atMost(Duration.ofSeconds(60))
        .until(() -> api.get("/v1/orders/" + id + "/events").json().get("data").size() == 5);
    assertThat(api.get("/v1/orders/" + id).json().at("/payment/captured_amount").asLong())
        .isEqualTo(2500);
  }

  private void startApp() throws IOException {
    int simPort = ((WebServerApplicationContext) simulators).getWebServer().getPort();
    String sim = "http://127.0.0.1:" + simPort + "/sim";
    List<String> cmd = new ArrayList<>();
    cmd.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    cmd.add("-jar");
    cmd.add(System.getProperty("payflow.bootJar"));
    cmd.add("--server.port=" + appPort);
    cmd.add("--management.server.port=0");
    cmd.add("--spring.datasource.url=" + Containers.POSTGRES.getJdbcUrl());
    cmd.add("--spring.datasource.username=" + Containers.POSTGRES.getUsername());
    cmd.add("--spring.datasource.password=" + Containers.POSTGRES.getPassword());
    cmd.add("--spring.kafka.bootstrap-servers=" + Containers.REDPANDA.getBootstrapServers());
    cmd.add("--payflow.simulator.enabled=false");
    for (String dep :
        List.of(
            "payment:payments",
            "inventory:inventory/reservations",
            "shipping:shipping/shipments")) {
      String[] p = dep.split(":", 2);
      cmd.add("--payflow.dependencies." + p[0] + ".base-url=" + sim + "/" + p[1]);
      cmd.add("--payflow.dependencies." + p[0] + ".api-key=" + SIM_KEY);
      cmd.add("--payflow.dependencies." + p[0] + ".read-timeout=60s");
    }
    cmd.add("--payflow.saga.lease=3s");
    cmd.add("--payflow.saga.unknown-outcome-delay=100ms");
    cmd.add(
        "--payflow.api-keys=acme:"
            + io.github.laiwf94.payflow.security.ApiKeys.hash(TestKeys.ACME));
    File out = log.toFile();
    app =
        new ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(out))
            .start();
    Api health = new Api(appPort, null);
    await()
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofMillis(200))
        .ignoreExceptions()
        .until(() -> health.get("/readyz").status() == 200);
  }

  /** SIGKILL: no shutdown hooks, no graceful stop, leases left behind. */
  private void killApp() throws InterruptedException {
    app.destroyForcibly();
    assertThat(app.waitFor(10, TimeUnit.SECONDS)).isTrue();
    assertThat(app.exitValue()).isEqualTo(137);
  }

  private static String state(String id) throws SQLException {
    try (Connection c =
            DriverManager.getConnection(
                Containers.POSTGRES.getJdbcUrl(),
                Containers.POSTGRES.getUsername(),
                Containers.POSTGRES.getPassword());
        PreparedStatement ps = c.prepareStatement("SELECT state FROM orders WHERE id = ?")) {
      ps.setObject(1, UUID.fromString(id));
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getString(1) : null;
      }
    }
  }

  private static int freePort() throws IOException {
    try (ServerSocket s = new ServerSocket(0)) {
      return s.getLocalPort();
    }
  }
}
