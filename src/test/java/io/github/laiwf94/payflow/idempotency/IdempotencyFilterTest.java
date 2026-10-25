package io.github.laiwf94.payflow.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.laiwf94.payflow.security.ApiKeyFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.support.TransactionTemplate;

class IdempotencyFilterTest {

  @Test
  void anUnreachableDatabaseIs503AndTheRequestDoesNotRun() throws Exception {
    IdempotencyStore store = mock(IdempotencyStore.class);
    when(store.claim(any(), any(), any(), any(), any()))
        .thenThrow(new CannotGetJdbcConnectionException("down"));
    IdempotencyFilter filter =
        new IdempotencyFilter(
            store, mock(TransactionTemplate.class), new ObjectMapper(), new SimpleMeterRegistry());
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/v1/orders");
    req.addHeader(IdempotencyFilter.HEADER, "k-1");
    req.setAttribute(ApiKeyFilter.MERCHANT_ATTRIBUTE, "acme");
    req.setContent("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    MockHttpServletResponse res = new MockHttpServletResponse();
    FilterChain chain =
        (rq, rs) -> {
          throw new AssertionError("the request must not run without its key");
        };
    filter.doFilter(req, res, chain);
    assertThat(res.getStatus()).isEqualTo(503);
    assertThat(res.getContentAsString()).contains("\"code\":\"unavailable\"");
  }
}
