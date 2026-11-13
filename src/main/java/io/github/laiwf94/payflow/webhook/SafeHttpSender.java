package io.github.laiwf94.payflow.webhook;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;

/**
 * Posts a webhook to a merchant's URL without letting that URL reach anything it shouldn't.
 *
 * <ul>
 *   <li>The host is resolved <b>once</b> per send, and <b>every</b> address it resolves to must
 *       pass the {@link AddressPolicy}; one refused address refuses the send. The connection then
 *       goes to one of those checked addresses, so a name that answers differently the second time
 *       (DNS rebinding) never gets a second lookup to answer.
 *   <li>The request carries the URL's own host in {@code Host}, and over TLS in SNI, and the
 *       certificate is verified against that host, not the address.
 *   <li>Redirects are not followed: a 3xx is an answer like any other, and it isn't a 2xx.
 *   <li>Everything, from the lookup to the last byte read, fits in one deadline. A watchdog closes
 *       the socket when it passes, so a server that trickles bytes can't hold a thread.
 *   <li>At most {@link Options#maxBody()} bytes of the body are read and the rest is dropped
 *       unread, and the status line and headers together are capped at {@link #MAX_HEAD} bytes.
 * </ul>
 *
 * Proxies are never used: a proxy would make its own lookup, after the check.
 */
public final class SafeHttpSender {

  /** Status line and headers, together. */
  public static final int MAX_HEAD = 16 * 1024;

  /** Looks a host name up. The default is the system resolver. */
  @FunctionalInterface
  public interface Resolver {
    List<InetAddress> resolve(String host) throws UnknownHostException;

    Resolver SYSTEM = host -> Arrays.asList(InetAddress.getAllByName(host));
  }

  /**
   * Limits for one send.
   *
   * @param connectTimeout time a connection to one address may take
   * @param timeout time the whole send may take, lookup and reading included
   * @param maxBody bytes of the response body kept
   */
  public record Options(Duration connectTimeout, Duration timeout, int maxBody) {

    public Options {
      if (connectTimeout.isNegative()
          || connectTimeout.isZero()
          || timeout.isNegative()
          || timeout.isZero()) {
        throw new IllegalArgumentException("timeouts must be positive");
      }
      if (maxBody < 0 || maxBody > 1024 * 1024) {
        throw new IllegalArgumentException("max body must be 0-1 MiB");
      }
    }
  }

  /** What a send came to. */
  public sealed interface Outcome permits Answered, Failed {

    /** From the start of the send until the outcome was known. */
    Duration latency();
  }

  /**
   * The server answered with an HTTP status.
   *
   * @param body the first {@link Options#maxBody()} bytes of the body, de-chunked, as UTF-8
   * @param truncated whether there was more body than that, or it stopped arriving in time
   * @param address the address that answered
   */
  public record Answered(
      int status, String body, boolean truncated, InetAddress address, Duration latency)
      implements Outcome {

    public boolean isSuccess() {
      return status >= 200 && status < 300;
    }
  }

  /** Why there was no HTTP answer. */
  public enum Failure {
    /** The host resolves to an address the policy refuses. */
    ADDRESS_REFUSED("address_refused"),
    /** The host has no addresses. */
    DNS("dns"),
    /** No address accepted a connection. */
    CONNECT("connect"),
    /** The TLS handshake failed, for example on the certificate. */
    TLS("tls"),
    /** The deadline passed before the status line and headers arrived. */
    TIMEOUT("timeout"),
    /** What came back wasn't HTTP/1.x, or its head was too large. */
    PROTOCOL("protocol"),
    /** The connection broke before an answer. */
    IO("io");

    private final String code;

    Failure(String code) {
      this.code = code;
    }

    public String code() {
      return code;
    }
  }

  /**
   * No HTTP answer.
   *
   * @param detail what happened, for the delivery log; never contains the request
   */
  public record Failed(Failure failure, String detail, Duration latency) implements Outcome {

    /** {@code code: detail}, at most 512 characters, as {@code webhook_attempts.error} holds. */
    public String describe() {
      String s = failure.code() + ": " + detail;
      return s.length() <= 512 ? s : s.substring(0, 509) + "...";
    }
  }

  private static final Set<String> RESERVED_HEADERS =
      Set.of(
          "host",
          "content-length",
          "connection",
          "transfer-encoding",
          "expect",
          "te",
          "upgrade",
          "keep-alive",
          "proxy-connection");
  private static final Pattern TOKEN = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
  private static final Pattern HEADER_VALUE = Pattern.compile("[\\x20-\\x7e\\t]*");
  private static final Pattern STATUS_LINE =
      Pattern.compile("HTTP/1\\.[01] ([1-5][0-9]{2})(?: .*)?");

  private static final ScheduledExecutorService WATCHDOG =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread t = new Thread(r, "webhook-send-watchdog");
            t.setDaemon(true);
            return t;
          });
  private static final ExecutorService LOOKUPS =
      Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("webhook-lookup-", 0).factory());

  private final AddressPolicy policy;
  private final Resolver resolver;
  private final SSLContext tls;
  private final Options options;
  private final String userAgent;

  public SafeHttpSender(
      AddressPolicy policy, Resolver resolver, SSLContext tls, Options options, String userAgent) {
    this.policy = policy;
    this.resolver = resolver;
    this.tls = tls;
    this.options = options;
    this.userAgent = userAgent;
  }

  public AddressPolicy policy() {
    return policy;
  }

  public Options options() {
    return options;
  }

  /**
   * Why a webhook to {@code url} would be refused, or empty when it wouldn't be, or can't be told
   * yet because the host doesn't resolve now; the send decides then. For registration, which
   * refuses an endpoint early rather than letting every delivery fail.
   *
   * @param url a URL as {@link WebhookUrls#normalize} returns it
   */
  public Optional<String> refusal(String url) {
    URI u = URI.create(url);
    String host = bareHost(u.getHost());
    long deadline = System.nanoTime() + options.timeout().toNanos();
    List<InetAddress> addresses;
    try {
      addresses = resolve(host, deadline);
    } catch (UnknownHostException | TimeoutException e) {
      return Optional.empty();
    }
    return refusal(host, addresses);
  }

  /**
   * POSTs {@code body} to {@code url} with {@code headers}, plus {@code Host}, {@code
   * Content-Length}, {@code Content-Type: application/json}, {@code User-Agent} and {@code
   * Connection: close}.
   *
   * @param url a URL as {@link WebhookUrls#normalize} returns it
   * @throws IllegalArgumentException when a header is malformed or one this class sets itself
   */
  public Outcome post(String url, Map<String, String> headers, byte[] body) {
    long start = System.nanoTime();
    long deadline = start + options.timeout().toNanos();
    URI u = URI.create(url);
    boolean https = "https".equals(u.getScheme());
    String host = bareHost(u.getHost());
    int port = u.getPort() > 0 ? u.getPort() : https ? 443 : 80;
    byte[] head = head(u, host, port, https, headers, body.length);

    List<InetAddress> addresses;
    try {
      addresses = resolve(host, deadline);
    } catch (UnknownHostException e) {
      return failed(Failure.DNS, "no address for " + host, start);
    } catch (TimeoutException e) {
      return failed(Failure.TIMEOUT, "looking up " + host + " took too long", start);
    }
    Optional<String> refused = refusal(host, addresses);
    if (refused.isPresent()) {
      return failed(Failure.ADDRESS_REFUSED, refused.get(), start);
    }

    Socket raw = null;
    InetAddress connected = null;
    String lastError = "no address";
    for (InetAddress a : addresses) {
      long left = remainingMillis(deadline);
      if (left <= 0) {
        break;
      }
      Socket s = new Socket(Proxy.NO_PROXY);
      try {
        s.connect(
            new InetSocketAddress(a, port),
            (int) Math.min(options.connectTimeout().toMillis(), left));
        raw = s;
        connected = a;
        break;
      } catch (IOException e) {
        closeQuietly(s);
        lastError = a.getHostAddress() + ": " + message(e);
      }
    }
    if (raw == null) {
      return remainingMillis(deadline) <= 0
          ? failed(Failure.TIMEOUT, "connecting took too long (" + lastError + ")", start)
          : failed(Failure.CONNECT, lastError, start);
    }

    Socket socket = raw;
    ScheduledFuture<?> watchdog =
        WATCHDOG.schedule(
            () -> closeQuietly(socket), remainingNanos(deadline), TimeUnit.NANOSECONDS);
    Socket conn = raw;
    try {
      raw.setSoTimeout((int) Math.max(1, remainingMillis(deadline)));
      raw.setTcpNoDelay(true);
      if (https) {
        try {
          conn = handshake(raw, host, port);
        } catch (IOException | IllegalArgumentException e) {
          return expired(deadline)
              ? failed(Failure.TIMEOUT, "TLS handshake took too long", start)
              : failed(Failure.TLS, message(e), start);
        }
      }
      OutputStream out = conn.getOutputStream();
      out.write(head);
      out.write(body);
      out.flush();
      return read(new BufferedInputStream(conn.getInputStream()), connected, deadline, start);
    } catch (IOException e) {
      return expired(deadline) || e instanceof SocketTimeoutException
          ? failed(Failure.TIMEOUT, "no answer in " + options.timeout().toMillis() + " ms", start)
          : failed(Failure.IO, message(e), start);
    } finally {
      watchdog.cancel(false);
      closeQuietly(conn);
      closeQuietly(raw);
    }
  }

  private Optional<String> refusal(String host, List<InetAddress> addresses) {
    for (InetAddress a : addresses) {
      Optional<String> why = policy.refusal(a);
      if (why.isPresent()) {
        String name =
            isLiteral(host) ? host + " is" : host + " resolves to " + a.getHostAddress() + ",";
        return Optional.of(name + " " + why.get() + "; webhooks go to public addresses only");
      }
    }
    return Optional.empty();
  }

  /** The addresses, looked up once, within the deadline. */
  private List<InetAddress> resolve(String host, long deadline)
      throws UnknownHostException, TimeoutException {
    if (isLiteral(host)) {
      return List.of(InetAddress.ofLiteral(host));
    }
    Future<List<InetAddress>> f = LOOKUPS.submit(() -> resolver.resolve(host));
    try {
      List<InetAddress> found = f.get(remainingNanos(deadline), TimeUnit.NANOSECONDS);
      if (found == null || found.isEmpty()) {
        throw new UnknownHostException(host);
      }
      return List.copyOf(found);
    } catch (ExecutionException e) {
      if (e.getCause() instanceof UnknownHostException u) {
        throw u;
      }
      UnknownHostException u = new UnknownHostException(host);
      u.initCause(e.getCause());
      throw u;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      f.cancel(true);
      throw new TimeoutException("interrupted");
    } catch (TimeoutException e) {
      f.cancel(true);
      throw e;
    }
  }

  private SSLSocket handshake(Socket raw, String host, int port) throws IOException {
    SSLSocket ssl = (SSLSocket) tls.getSocketFactory().createSocket(raw, host, port, true);
    SSLParameters p = ssl.getSSLParameters();
    // Verifies the certificate against the URL's host, as a browser would.
    p.setEndpointIdentificationAlgorithm("HTTPS");
    p.setProtocols(new String[] {"TLSv1.3", "TLSv1.2"});
    if (!isLiteral(host)) {
      p.setServerNames(List.of(new SNIHostName(host)));
    }
    ssl.setSSLParameters(p);
    ssl.startHandshake();
    return ssl;
  }

  private byte[] head(
      URI u, String host, int port, boolean https, Map<String, String> headers, int length) {
    String target = u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath();
    if (u.getRawQuery() != null) {
      target += "?" + u.getRawQuery();
    }
    String hostHeader = host.indexOf(':') >= 0 ? "[" + host + "]" : host;
    if (port != (https ? 443 : 80)) {
      hostHeader += ":" + port;
    }
    Map<String, String> all = new LinkedHashMap<>();
    all.put("Host", hostHeader);
    all.put("User-Agent", userAgent);
    all.put("Content-Type", "application/json");
    all.put("Accept", "*/*");
    all.put("Accept-Encoding", "identity");
    for (Map.Entry<String, String> h : headers.entrySet()) {
      String name = h.getKey();
      if (!TOKEN.matcher(name).matches() || !HEADER_VALUE.matcher(h.getValue()).matches()) {
        throw new IllegalArgumentException("malformed header " + name);
      }
      if (RESERVED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
        throw new IllegalArgumentException("the sender sets " + name + " itself");
      }
      all.keySet().removeIf(k -> k.equalsIgnoreCase(name));
      all.put(name, h.getValue());
    }
    all.put("Content-Length", Integer.toString(length));
    all.put("Connection", "close");
    StringBuilder sb = new StringBuilder("POST ").append(target).append(" HTTP/1.1\r\n");
    all.forEach((k, v) -> sb.append(k).append(": ").append(v).append("\r\n"));
    sb.append("\r\n");
    return sb.toString().getBytes(StandardCharsets.US_ASCII);
  }

  private Outcome read(InputStream in, InetAddress from, long deadline, long start)
      throws IOException {
    Head head = new Head(in);
    int status;
    while (true) {
      String line = head.line();
      if (line == null) {
        return expired(deadline)
            ? failed(Failure.TIMEOUT, "no answer in " + options.timeout().toMillis() + " ms", start)
            : failed(Failure.IO, "connection closed before a status line", start);
      }
      var m = STATUS_LINE.matcher(line);
      if (!m.matches()) {
        return failed(Failure.PROTOCOL, "not an HTTP/1.x status line", start);
      }
      status = Integer.parseInt(m.group(1));
      Map<String, String> fields = head.fields();
      if (fields == null) {
        return failed(Failure.PROTOCOL, "response head over " + MAX_HEAD + " bytes", start);
      }
      // Interim answers (100 Continue and the like) are skipped; 101 would be a switch, which a
      // POST without Upgrade never asks for, so it is taken as the answer.
      if (status >= 200 || status == 101) {
        Duration latency = since(start);
        Body b = body(in, status, fields);
        return new Answered(
            status, new String(b.bytes(), StandardCharsets.UTF_8), b.truncated(), from, latency);
      }
    }
  }

  private static final class Body {
    private final byte[] bytes;
    private final boolean truncated;

    Body(byte[] bytes, boolean truncated) {
      this.bytes = bytes;
      this.truncated = truncated;
    }

    byte[] bytes() {
      return bytes;
    }

    boolean truncated() {
      return truncated;
    }
  }

  /** As much of the body as fits the cap and the deadline; a failure here keeps what was read. */
  private Body body(InputStream in, int status, Map<String, String> fields) {
    ByteArrayOutputStream kept = new ByteArrayOutputStream();
    int cap = options.maxBody();
    if (status == 204 || status == 304 || status == 101) {
      return new Body(new byte[0], false);
    }
    try {
      String te = fields.getOrDefault("transfer-encoding", "");
      if (te.toLowerCase(Locale.ROOT).contains("chunked")) {
        Head lines = new Head(in);
        while (true) {
          String sizeLine = lines.line();
          if (sizeLine == null) {
            return new Body(kept.toByteArray(), true);
          }
          int semi = sizeLine.indexOf(';');
          long size =
              Long.parseLong((semi < 0 ? sizeLine : sizeLine.substring(0, semi)).strip(), 16);
          if (size == 0) {
            return new Body(kept.toByteArray(), false);
          }
          if (kept.size() + size > cap) {
            copy(in, kept, cap - kept.size());
            return new Body(kept.toByteArray(), true);
          }
          copy(in, kept, (int) size);
          lines.line(); // the CRLF after the chunk
        }
      }
      String cl = fields.get("content-length");
      if (cl != null) {
        long length = Long.parseLong(cl.strip());
        int want = (int) Math.min(length, cap);
        int got = copy(in, kept, want);
        return new Body(kept.toByteArray(), got < want || length > cap);
      }
      int got = copy(in, kept, cap);
      return new Body(kept.toByteArray(), got == cap && in.read() >= 0);
    } catch (IOException | NumberFormatException e) {
      return new Body(kept.toByteArray(), true);
    }
  }

  /** Copies up to {@code n} bytes, fewer if the stream ends; returns how many. */
  private static int copy(InputStream in, ByteArrayOutputStream out, int n) throws IOException {
    byte[] buf = new byte[Math.min(Math.max(n, 1), 8192)];
    int total = 0;
    while (total < n) {
      int r = in.read(buf, 0, Math.min(buf.length, n - total));
      if (r < 0) {
        break;
      }
      out.write(buf, 0, r);
      total += r;
    }
    return total;
  }

  /** Reads CRLF-terminated lines, at most {@link #MAX_HEAD} bytes in all. */
  private static final class Head {
    private final InputStream in;
    private int used;

    Head(InputStream in) {
      this.in = in;
    }

    /** The next line without its line end, or null at end of stream or over the cap. */
    String line() throws IOException {
      ByteArrayOutputStream b = new ByteArrayOutputStream();
      while (true) {
        int c = in.read();
        if (c < 0 || ++used > MAX_HEAD) {
          return null;
        }
        if (c == '\n') {
          byte[] bytes = b.toByteArray();
          int len =
              bytes.length > 0 && bytes[bytes.length - 1] == '\r' ? bytes.length - 1 : bytes.length;
          return new String(bytes, 0, len, StandardCharsets.ISO_8859_1);
        }
        b.write(c);
      }
    }

    /** Header fields with lowercased names until the empty line, or null over the cap. */
    Map<String, String> fields() throws IOException {
      Map<String, String> f = new LinkedHashMap<>();
      while (true) {
        String line = line();
        if (line == null) {
          return null;
        }
        if (line.isEmpty()) {
          return f;
        }
        int colon = line.indexOf(':');
        if (colon > 0) {
          f.merge(
              line.substring(0, colon).strip().toLowerCase(Locale.ROOT),
              line.substring(colon + 1).strip(),
              (a, b) -> a + ", " + b);
        }
      }
    }
  }

  static boolean isLiteral(String host) {
    if (host.indexOf(':') >= 0) {
      return true;
    }
    return host.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}");
  }

  private static String bareHost(String host) {
    return host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
  }

  private static Failed failed(Failure f, String detail, long start) {
    return new Failed(f, detail, since(start));
  }

  private static Duration since(long start) {
    return Duration.ofNanos(System.nanoTime() - start);
  }

  private static long remainingNanos(long deadline) {
    return Math.max(0, deadline - System.nanoTime());
  }

  private static long remainingMillis(long deadline) {
    return TimeUnit.NANOSECONDS.toMillis(remainingNanos(deadline));
  }

  private static boolean expired(long deadline) {
    return System.nanoTime() - deadline >= 0;
  }

  private static String message(Throwable e) {
    if (e instanceof SSLException && e.getCause() != null && e.getCause().getMessage() != null) {
      return e.getCause().getMessage();
    }
    String m = e.getMessage();
    return m == null ? e.getClass().getSimpleName() : m;
  }

  private static void closeQuietly(Socket s) {
    if (s != null) {
      try {
        s.close();
      } catch (IOException ignored) {
        // closing; nothing left to do
      }
    }
  }
}
