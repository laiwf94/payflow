package io.github.laiwf94.payflow.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;

/**
 * The one error shape every endpoint returns: {@code {"error":{"code","message","request_id"}}}.
 * Codes are the stable {@link ApiError} list; messages are for humans and may change.
 */
public final class ApiErrors {

  private ApiErrors() {}

  public static Map<String, Object> body(ApiError error, String message, String requestId) {
    Map<String, Object> err = new LinkedHashMap<>();
    err.put("code", error.code());
    err.put("message", message);
    err.put("request_id", requestId);
    return Map.of("error", err);
  }

  /** Writes an error directly, for filters that answer before Spring MVC is involved. */
  public static void write(
      HttpServletRequest req,
      HttpServletResponse res,
      ObjectMapper json,
      ApiError error,
      String message)
      throws IOException {
    res.setStatus(error.status());
    res.setContentType(MediaType.APPLICATION_JSON_VALUE);
    json.writeValue(res.getOutputStream(), body(error, message, RequestIds.of(req)));
  }
}
