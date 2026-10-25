package io.github.laiwf94.payflow;

import io.github.laiwf94.payflow.sim.SimulatorApplication;
import java.util.Arrays;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Payment orchestration service: turns an order into inventory, payment and shipping calls.
 *
 * <p>{@code payflow simulator [args]} runs only the fake providers, as their own process.
 */
@SpringBootApplication
public class PayflowApplication {

  public static void main(String[] args) {
    if (args.length > 0 && args[0].equals("simulator")) {
      SimulatorApplication.start(Arrays.copyOfRange(args, 1, args.length));
      return;
    }
    start(args);
  }

  static ConfigurableApplicationContext start(String... args) {
    return SpringApplication.run(PayflowApplication.class, args);
  }
}
