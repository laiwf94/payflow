package io.github.laiwf94.payflow.security;

import io.github.laiwf94.payflow.api.ApiErrors;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/** Requires {@code Authorization: Bearer <key>} on {@code /v1/*} and records the merchant. */
public final class ApiKeyFilter extends OncePerRequestFilter {

  public static final String MERCHANT_ATTRIBUTE = ApiKeyFilter.class.getName() + ".merchant";

  private final ApiKeys keys;
  private final ObjectMapper json;

  public ApiKeyFilter(ApiKeys keys, ObjectMapper json) {
    this.keys = keys;
    this.json = json;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    String header = req.getHeader(HttpHeaders.AUTHORIZATION);
    Optional<String> merchant =
        header != null && header.startsWith("Bearer ")
            ? keys.authenticate(header.substring("Bearer ".length()))
            : Optional.empty();
    if (merchant.isEmpty()) {
      res.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
      ApiErrors.write(req, res, json, 401, "unauthenticated", "missing or invalid API key");
      return;
    }
    req.setAttribute(MERCHANT_ATTRIBUTE, merchant.get());
    MDC.put("merchant_id", merchant.get());
    try {
      chain.doFilter(req, res);
    } finally {
      MDC.remove("merchant_id");
    }
  }
}
