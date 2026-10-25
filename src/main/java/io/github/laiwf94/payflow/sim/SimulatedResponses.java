package io.github.laiwf94.payflow.sim;

import java.time.Duration;
import java.util.Map;
import java.util.function.Function;
import org.springframework.http.ResponseEntity;

/** Turns a {@link Simulated} result into what the network would deliver. */
class SimulatedResponses {

  private final SimulatorSettings settings;

  SimulatedResponses(SimulatorSettings settings) {
    this.settings = settings;
  }

  <T> ResponseEntity<?> respond(Simulated<T> result, Function<T, ResponseEntity<?>> onAnswer) {
    sleep(result.latency());
    return switch (result) {
      case Simulated.Answered<T> a -> onAnswer.apply(a.value());
      case Simulated.Unavailable<T> u ->
          ResponseEntity.status(503)
              .body(Map.of("error", Map.of("code", "unavailable", "message", "try again later")));
      case Simulated.Unanswered<T> u -> {
        // Hold the connection past the caller's read timeout; by the time this returns the
        // caller has given up and can't tell whether the call was applied.
        sleep(settings.answerDelay());
        yield ResponseEntity.status(504)
            .body(Map.of("error", Map.of("code", "timeout", "message", "no answer")));
      }
    };
  }

  private static void sleep(Duration d) {
    if (d.isZero()) {
      return;
    }
    try {
      Thread.sleep(d);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
