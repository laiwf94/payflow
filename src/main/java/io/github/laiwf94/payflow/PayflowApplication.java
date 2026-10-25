package io.github.laiwf94.payflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Payment orchestration service: turns an order into inventory, payment and shipping calls. */
@SpringBootApplication
public class PayflowApplication {

  public static void main(String[] args) {
    SpringApplication.run(PayflowApplication.class, args);
  }
}
