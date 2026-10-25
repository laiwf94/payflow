package io.github.laiwf94.payflow.sim;

import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Error bodies for the simulator routes, shaped like a typical provider's. */
@EnabledSimulator
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackageClasses = SimulatorErrors.class)
class SimulatorErrors {

  @ExceptionHandler(SimulatorException.class)
  ResponseEntity<?> business(SimulatorException e) {
    return ResponseEntity.status(e.status()).body(body(e.code(), e.getMessage()));
  }

  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MissingServletRequestParameterException.class
  })
  ResponseEntity<?> malformed(Exception e) {
    return ResponseEntity.badRequest().body(body("invalid_request", "malformed request"));
  }

  private static Map<String, Object> body(String code, String message) {
    return Map.of("error", Map.of("code", code, "message", message));
  }
}
