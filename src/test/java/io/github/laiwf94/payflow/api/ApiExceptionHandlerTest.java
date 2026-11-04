package io.github.laiwf94.payflow.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLTransientConnectionException;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.mock.web.MockHttpServletRequest;

class ApiExceptionHandlerTest {

  private final ApiExceptionHandler handler = new ApiExceptionHandler();

  static java.util.List<DataAccessException> unreachable() {
    return java.util.List.of(
        new CannotGetJdbcConnectionException(
            "pool timed out", new SQLTransientConnectionException("timeout")),
        new TransientDataAccessResourceException("connection reset"),
        new RecoverableDataAccessException("connection lost"));
  }

  @ParameterizedTest
  @MethodSource("unreachable")
  void anUnreachableDatabaseIs503Unavailable(DataAccessException e) {
    ResponseEntity<Map<String, Object>> r =
        handler.databaseUnreachable(e, new MockHttpServletRequest("GET", "/v1/orders"));
    assertThat(r.getStatusCode().value()).isEqualTo(503);
    assertThat(r.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
    @SuppressWarnings("unchecked")
    Map<String, Object> error = (Map<String, Object>) r.getBody().get("error");
    assertThat(error).containsEntry("code", "unavailable").containsKey("request_id");
  }

  /** A broken query or a constraint is still a 500: only an outage is worth retrying. */
  @ParameterizedTest
  @MethodSource("unreachable")
  void theAdviceRoutesEachOutageToTheUnavailableHandler(DataAccessException e) throws Exception {
    var resolver =
        new org.springframework.web.method.annotation.ExceptionHandlerMethodResolver(
            ApiExceptionHandler.class);
    assertThat(resolver.resolveMethod(e).getName()).isEqualTo("databaseUnreachable");
    assertThat(resolver.resolveMethod(new DataIntegrityViolationException("x")).getName())
        .isEqualTo("unexpected");
  }
}
