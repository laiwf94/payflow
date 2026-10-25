package io.github.laiwf94.payflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

/** Payment orchestration service: turns an order into inventory, payment and shipping calls. */
@SpringBootApplication
public class PayflowApplication {

  public static void main(String[] args) {
    start(args);
  }

  static ConfigurableApplicationContext start(String... args) {
    return SpringApplication.run(PayflowApplication.class, args);
  }
}
