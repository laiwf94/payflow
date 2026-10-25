package io.github.laiwf94.payflow.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Caps request bodies on the merchant API. A declared Content-Length over the cap is refused before
 * anything is read; a chunked body is cut off as soon as it crosses the cap.
 */
public final class BodyLimitFilter extends OncePerRequestFilter {

  /** Fifty lines with full addresses fit in well under this. */
  public static final int MAX_BODY_BYTES = 64 * 1024;

  /** Thrown from the stream when a body without a length grows past the cap. */
  static final class PayloadTooLarge extends IOException {
    private static final long serialVersionUID = 1L;

    PayloadTooLarge() {
      super("request body is larger than " + MAX_BODY_BYTES + " bytes");
    }
  }

  private final ObjectMapper json;

  public BodyLimitFilter(ObjectMapper json) {
    this.json = json;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    if (req.getContentLengthLong() > MAX_BODY_BYTES) {
      ApiErrors.write(req, res, json, 413, "payload_too_large", new PayloadTooLarge().getMessage());
      return;
    }
    chain.doFilter(new Limited(req), res);
  }

  private static final class Limited extends HttpServletRequestWrapper {
    private ServletInputStream stream;

    Limited(HttpServletRequest req) {
      super(req);
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
      if (stream == null) {
        stream = new LimitedStream(super.getInputStream());
      }
      return stream;
    }
  }

  private static final class LimitedStream extends ServletInputStream {
    private final ServletInputStream in;
    private long read;

    LimitedStream(ServletInputStream in) {
      this.in = in;
    }

    @Override
    public int read() throws IOException {
      int b = in.read();
      if (b >= 0) {
        count(1);
      }
      return b;
    }

    @Override
    public int read(byte[] buf, int off, int len) throws IOException {
      int n = in.read(buf, off, len);
      if (n > 0) {
        count(n);
      }
      return n;
    }

    private void count(int n) throws PayloadTooLarge {
      read += n;
      if (read > MAX_BODY_BYTES) {
        throw new PayloadTooLarge();
      }
    }

    @Override
    public boolean isFinished() {
      return in.isFinished();
    }

    @Override
    public boolean isReady() {
      return in.isReady();
    }

    @Override
    public void setReadListener(ReadListener listener) {
      in.setReadListener(listener);
    }
  }
}
