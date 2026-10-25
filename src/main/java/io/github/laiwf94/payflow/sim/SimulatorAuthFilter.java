package io.github.laiwf94.payflow.sim;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/** Requires {@code Authorization: Bearer <simulator key>} on every simulator route. */
final class SimulatorAuthFilter extends OncePerRequestFilter {

  private final SimulatorKey key;

  SimulatorAuthFilter(SimulatorKey key) {
    this.key = key;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String header = request.getHeader(HttpHeaders.AUTHORIZATION);
    String presented =
        header != null && header.startsWith("Bearer ")
            ? header.substring("Bearer ".length())
            : null;
    if (!key.matches(presented)) {
      response.setStatus(401);
      response.setContentType(MediaType.APPLICATION_JSON_VALUE);
      response
          .getWriter()
          .write("{\"error\":{\"code\":\"unauthenticated\",\"message\":\"missing or wrong key\"}}");
      return;
    }
    chain.doFilter(request, response);
  }
}
