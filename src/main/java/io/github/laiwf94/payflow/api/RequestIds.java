package io.github.laiwf94.payflow.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request a correlation id: the caller's {@code X-Request-Id} if it is short and plain,
 * otherwise a fresh one. It goes into every log line, every error body, the response header, the
 * orders the request creates and, from there, every event and saga step for those orders.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIds extends OncePerRequestFilter {

  public static final String HEADER = "X-Request-Id";
  static final String ATTRIBUTE = RequestIds.class.getName();
  public static final String MDC_KEY = "correlation_id";

  private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._-]{1,64}");

  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    String given = req.getHeader(HEADER);
    String id =
        given != null && SAFE.matcher(given).matches() ? given : UUID.randomUUID().toString();
    req.setAttribute(ATTRIBUTE, id);
    res.setHeader(HEADER, id);
    MDC.put(MDC_KEY, id);
    try {
      chain.doFilter(req, res);
    } finally {
      MDC.remove(MDC_KEY);
    }
  }

  /** The current request's id. */
  public static String of(HttpServletRequest req) {
    Object id = req.getAttribute(ATTRIBUTE);
    return id == null ? "unknown" : id.toString();
  }
}
