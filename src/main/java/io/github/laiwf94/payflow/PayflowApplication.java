package io.github.laiwf94.payflow;

import io.github.laiwf94.payflow.security.ApiKeys;
import io.github.laiwf94.payflow.sim.SimulatorApplication;
import java.util.Arrays;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Payment orchestration service: turns an order into inventory, payment and shipping calls.
 *
 * <p>{@code payflow simulator [args]} runs only the fake providers, as their own process. {@code
 * payflow keygen <merchant>} prints a new API key and the {@code PAYFLOW_API_KEYS} entry for it.
 */
@SpringBootApplication
public class PayflowApplication {

  public static void main(String[] args) {
    if (args.length == 2 && args[0].equals("keygen")) {
      System.out.print(keygen(args[1]));
      return;
    }
    if (args.length > 0 && args[0].equals("simulator")) {
      SimulatorApplication.start(Arrays.copyOfRange(args, 1, args.length));
      return;
    }
    start(args);
  }

  /** The key goes to the merchant; only the hash goes into configuration. */
  static String keygen(String merchant) {
    String key = ApiKeys.generate();
    // Validates the merchant id the same way startup will.
    ApiKeys.parse(merchant + ":" + ApiKeys.hash(key));
    return "key:  "
        + key
        + "\nPAYFLOW_API_KEYS entry: "
        + merchant
        + ":"
        + ApiKeys.hash(key)
        + "\n";
  }

  static ConfigurableApplicationContext start(String... args) {
    return SpringApplication.run(PayflowApplication.class, args);
  }
}
