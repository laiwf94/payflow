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

  /**
   * Every operation a simulator runs under a fault plan can be given one through the control API,
   * so the demo and the compose stack can fault the same calls the in-process tests do.
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
      }
    }
    assertThat(used).contains("payment.refund", "inventory.restock", "shipping.create");
    assertThat(SimulatorControlController.OPERATIONS).containsExactlyInAnyOrderElementsOf(used);
  }
}
