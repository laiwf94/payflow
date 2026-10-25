package io.github.laiwf94.payflow.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;

/**
 * The one error shape every endpoint returns: {@code {"error":{"code","message","request_id"}}}.
 * Codes are stable and documented; messages are for humans and may change.
 */
public final class ApiErrors {

  private ApiErrors() {}

  public static Map<String, Object> body(String code, String message, String requestId) {
    Map<String, Object> err = new LinkedHashMap<>();
    err.put("code", code);
    err.put("message", message);
    err.put("request_id", requestId);
    return Map.of("error", err);
  }

  /** Writes an error directly, for filters that answer before Spring MVC is involved. */
  public static void write(
      HttpServletRequest req,
      HttpServletResponse res,
      ObjectMapper json,
      int status,
      String code,
      String message)
      throws IOException {
    res.setStatus(status);
    res.setContentType(MediaType.APPLICATION_JSON_VALUE);
    json.writeValue(res.getOutputStream(), body(code, message, RequestIds.of(req)));
  }
}
