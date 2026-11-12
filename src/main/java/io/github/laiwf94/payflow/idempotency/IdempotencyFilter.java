package io.github.laiwf94.payflow.idempotency;

import io.github.laiwf94.payflow.api.ApiError;
import io.github.laiwf94.payflow.api.ApiErrors;
import io.github.laiwf94.payflow.api.RequestIds;
import io.github.laiwf94.payflow.security.ApiKeyFilter;
import io.github.laiwf94.payflow.security.SecretBox;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;

/**
 * Stripe-style idempotency for every POST on the merchant API.
 *
 * <ul>
 *   <li>Same key, same request: the first response is replayed, errors included.
 *   <li>Same key, different method, path or body: 422 {@code idempotency_key_reused}.
 *   <li>Same key while the first request is still running: 409 {@code idempotency_key_in_use}.
 * </ul>
 *
 * <p>The whole request runs in one database transaction and the response is stored in that same
 * transaction, so the order and the key's response commit together or not at all. A 5xx rolls the
 * work back and releases the key, so the client's retry runs fresh.
 *
 * <p>An answer that carries a secret shown only once, such as a new webhook endpoint's signing
 * secret, is marked by its controller with {@link #sealStoredAnswer}. Its stored copy is then
 * sealed with the {@link SecretBox}, bound to the merchant and key, so the secret isn't kept in the
 * clear for the key's lifetime; a replay opens it and answers byte for byte as the first time.
 */
public final class IdempotencyFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);

  public static final String HEADER = "Idempotency-Key";
  public static final String REPLAYED_HEADER = "Idempotent-Replayed";

  private static final String SEAL_ATTRIBUTE = IdempotencyFilter.class.getName() + ".seal";

  /** How a sealed answer is stored: this prefix, then key id, nonce and ciphertext. */
  static final String SEALED_PREFIX = "sealed:v1:";

  /** Printable ASCII without spaces, at most 255 characters. */
  private static final Pattern KEY = Pattern.compile("[\\x21-\\x7e]{1,255}");

  private final IdempotencyStore store;
  private final TransactionTemplate tx;
  private final ObjectMapper json;
  private final ObjectMapper canonical;
  private final MeterRegistry meters;
  private final SecretBox box;

  public IdempotencyFilter(
      IdempotencyStore store,
      TransactionTemplate tx,
      ObjectMapper json,
      MeterRegistry meters,
      SecretBox box) {
    this.store = store;
    this.box = box;
    this.tx = tx;
    this.json = json;
    this.canonical =
        json.rebuild().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true).build();
    this.meters = meters;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !"POST".equals(request.getMethod());
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    String key = req.getHeader(HEADER);
    if (key == null || !KEY.matcher(key).matches()) {
      ApiErrors.write(
          req,
          res,
          json,
          ApiError.INVALID_ARGUMENT,
          "Idempotency-Key header is required: 1-255 printable characters without spaces");
      return;
    }
    byte[] body;
    try {
      body = req.getInputStream().readAllBytes();
    } catch (IOException e) {
      ApiErrors.write(req, res, json, ApiError.PAYLOAD_TOO_LARGE, e.getMessage());
      return;
    }
    String merchant = (String) req.getAttribute(ApiKeyFilter.MERCHANT_ATTRIBUTE);
    String path = req.getRequestURI();
    IdempotencyStore.Claim claim;
    try {
      claim = store.claim(merchant, key, req.getMethod(), path, hash(req.getMethod(), path, body));
    } catch (DataAccessException e) {
      log.error("can't reach the database to claim an idempotency key", e);
      ApiErrors.write(
          req, res, json, ApiError.UNAVAILABLE, "temporarily unavailable; retry with the same key");
      return;
    }
    switch (claim) {
      case IdempotencyStore.Claim.Mismatch m -> {
        count("reused");
        ApiErrors.write(
            req,
            res,
            json,
            ApiError.IDEMPOTENCY_KEY_REUSED,
            "this Idempotency-Key was used for a different request");
      }
      case IdempotencyStore.Claim.InProgress p -> {
        count("in_progress");
        ApiErrors.write(
            req,
            res,
            json,
            ApiError.IDEMPOTENCY_KEY_IN_USE,
            "a request with this Idempotency-Key is still running; retry later");
      }
      case IdempotencyStore.Claim.Replay r -> {
        String stored;
        try {
          stored = open(merchant, key, r.body());
        } catch (SecretBox.CannotOpen e) {
          log.error("can't open the stored answer for an idempotency key: {}", e.getMessage());
          ApiErrors.write(
              req, res, json, ApiError.INTERNAL, "the stored answer for this key can't be read");
          return;
        }
        count("replayed");
        res.setStatus(r.status());
        res.setHeader(REPLAYED_HEADER, "true");
        if (r.location() != null) {
          res.setHeader(HttpHeaders.LOCATION, r.location());
        }
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.getOutputStream().write(stored.getBytes(StandardCharsets.UTF_8));
      }
      case IdempotencyStore.Claim.Proceed p -> {
        count("new");
        run(new CachedBody(req, body), res, chain, merchant, key, p);
      }
    }
  }

  private void run(
      HttpServletRequest req,
      HttpServletResponse res,
      FilterChain chain,
      String merchant,
      String key,
      IdempotencyStore.Claim.Proceed claim)
      throws IOException, ServletException {
    ContentCachingResponseWrapper wrapped = new ContentCachingResponseWrapper(res);
    boolean[] committed = {false};
    try {
      tx.executeWithoutResult(
          status -> {
            try {
              chain.doFilter(req, wrapped);
            } catch (IOException | ServletException e) {
              throw new ChainFailure(e);
            }
            int code = wrapped.getStatus();
            if (code >= 500) {
              status.setRollbackOnly();
              return;
            }
            String bodyText = new String(wrapped.getContentAsByteArray(), StandardCharsets.UTF_8);
            if (Boolean.TRUE.equals(req.getAttribute(SEAL_ATTRIBUTE))) {
              bodyText = seal(merchant, key, bodyText);
            }
            if (!store.complete(
                merchant,
                key,
                claim.token(),
                code,
                bodyText,
                wrapped.getHeader(HttpHeaders.LOCATION))) {
              // Our lock expired and another request took the key over; let that one win.
              status.setRollbackOnly();
              wrapped.reset();
              return;
            }
            committed[0] = true;
          });
    } catch (ChainFailure e) {
      store.release(merchant, key, claim.token());
      Throwable cause = e.getCause();
      if (cause instanceof IOException io) {
        throw io;
      }
      throw (ServletException) cause;
    } catch (RuntimeException e) {
      store.release(merchant, key, claim.token());
      throw e;
    }
    if (!committed[0]) {
      if (wrapped.getStatus() >= 500) {
        store.release(merchant, key, claim.token());
      } else {
        res.setHeader(RequestIds.HEADER, RequestIds.of(req));
        ApiErrors.write(
            req,
            res,
            json,
            ApiError.IDEMPOTENCY_KEY_IN_USE,
            "a request with this Idempotency-Key took over; retry later");
        return;
      }
    }
    wrapped.copyBodyToResponse();
  }

  /**
   * Marks the answer to this request as carrying a secret, so its stored copy is sealed. Called by
   * the controller while the request runs; outside this filter it does nothing.
   */
  public static void sealStoredAnswer(HttpServletRequest req) {
    req.setAttribute(SEAL_ATTRIBUTE, true);
  }

  private String seal(String merchant, String key, String body) {
    SecretBox.Sealed s = box.seal(body, context(merchant, key));
    Base64.Encoder b64 = Base64.getEncoder();
    return SEALED_PREFIX
        + s.keyId()
        + ":"
        + b64.encodeToString(s.nonce())
        + ":"
        + b64.encodeToString(s.ciphertext());
  }

  /** The stored answer as it was sent: opened when it was sealed, as is otherwise. */
  private String open(String merchant, String key, String stored) {
    if (!stored.startsWith(SEALED_PREFIX)) {
      return stored;
    }
    String[] parts = stored.substring(SEALED_PREFIX.length()).split(":", -1);
    if (parts.length != 3) {
      throw new SecretBox.CannotOpen("stored answer is not a sealed value");
    }
    try {
      Base64.Decoder b64 = Base64.getDecoder();
      return box.open(
          new SecretBox.Sealed(parts[0], b64.decode(parts[1]), b64.decode(parts[2])),
          context(merchant, key));
    } catch (IllegalArgumentException e) {
      throw new SecretBox.CannotOpen("stored answer is not a sealed value");
    }
  }

  private static String context(String merchant, String key) {
    return "idempotency_keys.response\n" + merchant + "\n" + key;
  }

  /**
   * Hash of method, path and body. JSON bodies are hashed in a canonical form (sorted keys, no
   * whitespace), so a client that reorders fields on retry still matches.
   */
  public byte[] hash(String method, String path, byte[] body) {
    byte[] normal = body;
    if (body.length > 0) {
      try {
        JsonNode tree = json.readTree(body);
        Object asObject = canonical.treeToValue(tree, Object.class);
        normal = canonical.writeValueAsBytes(asObject);
      } catch (JacksonException e) {
        normal = body;
      }
    }
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      md.update(method.getBytes(StandardCharsets.UTF_8));
      md.update((byte) 0);
      md.update(path.getBytes(StandardCharsets.UTF_8));
      md.update((byte) 0);
      md.update(normal);
      return md.digest();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is always available", e);
    }
  }

  private void count(String outcome) {
    meters.counter("payflow.idempotency.requests", "outcome", outcome).increment();
  }

  /** Carries a checked exception out of the transaction callback. */
  private static final class ChainFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;

    ChainFailure(Exception cause) {
      super(cause);
    }
  }

  /** The request with its body already read, so the controller can read it again. */
  private static final class CachedBody extends HttpServletRequestWrapper {
    private final byte[] body;

    CachedBody(HttpServletRequest req, byte[] body) {
      super(req);
      this.body = body;
    }

    @Override
    public ServletInputStream getInputStream() {
      ByteArrayInputStream in = new ByteArrayInputStream(body);
      return new ServletInputStream() {
        @Override
        public int read() {
          return in.read();
        }

        @Override
        public int read(byte[] b, int off, int len) {
          return in.read(b, off, len);
        }

        @Override
        public boolean isFinished() {
          return in.available() == 0;
        }

        @Override
        public boolean isReady() {
          return true;
        }

        @Override
        public void setReadListener(ReadListener listener) {
          throw new UnsupportedOperationException("synchronous only");
        }
      };
    }

    @Override
    public int getContentLength() {
      return body.length;
    }

    @Override
    public long getContentLengthLong() {
      return body.length;
    }
  }
}
