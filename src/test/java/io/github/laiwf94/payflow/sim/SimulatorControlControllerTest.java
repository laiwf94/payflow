package io.github.laiwf94.payflow.sim;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class SimulatorControlControllerTest {

  private static final Pattern OPERATION =
      Pattern.compile("\"((?:payment|inventory|shipping)\\.[a-z]+)\"");

  /** A payment provider's operation, named relative to that provider: {@code op("capture")}. */
  private static final Pattern PROVIDER_OPERATION = Pattern.compile("\\bop\\(\"([a-z]+)\"\\)");

  /**
   * Every operation a simulator runs under a fault plan can be given one through the control API,
   * so the demo and the compose stack can fault the same calls the in-process tests do. A payment
   * provider's operations exist once for each provider, under its own prefix.
   */
  @Test
  void everyFaultedOperationCanBeConfigured() throws IOException {
    Set<String> used = new TreeSet<>();
    Path sim = Path.of("src/main/java/io/github/laiwf94/payflow/sim");
    try (Stream<Path> files = Files.list(sim)) {
      for (Path f : files.filter(p -> p.toString().endsWith("Simulator.java")).toList()) {
        Matcher m = OPERATION.matcher(Files.readString(f));
        while (m.find()) {
          used.add(m.group(1));
        }
        Matcher p = PROVIDER_OPERATION.matcher(Files.readString(f));
        while (p.find()) {
          used.add(PaymentProviderSimulator.PRIMARY_OPERATIONS + "." + p.group(1));
          used.add(SimulatorConfig.SECONDARY_OPERATIONS + "." + p.group(1));
        }
      }
    }
    assertThat(used)
        .contains("payment.refund", "secondary.refund", "inventory.restock", "shipping.create");
    assertThat(SimulatorControlController.OPERATIONS).containsExactlyInAnyOrderElementsOf(used);
  }
}
