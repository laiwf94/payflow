package io.github.laiwf94.payflow.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.laiwf94.payflow.webhook.SafeHttpSender.Answered;
import io.github.laiwf94.payflow.webhook.SafeHttpSender.Failed;
import io.github.laiwf94.payflow.webhook.SafeHttpSender.Failure;
import io.github.laiwf94.payflow.webhook.SafeHttpSender.Outcome;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.ExtendedSSLSession;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The webhook sender against real sockets on loopback: one lookup per send, every address checked,
 * the connection to the checked address with the URL's own host, no redirects, and a deadline and a
 * size cap on what comes back.
 */
class SafeHttpSenderTest {

  private static final byte[] PAYLOAD =
      "{\"type\":\"order.created\"}".getBytes(StandardCharsets.UTF_8);
  private static final InetAddress LOOPBACK = InetAddress.ofLiteral("127.0.0.1");

  /** The test servers listen on 127.0.0.1, so that one address is let through; 127.0.0.2 isn't. */
  private static final AddressPolicy POLICY = AddressPolicy.allowing(List.of("127.0.0.1/32"));

  private static final SafeHttpSender.Options QUICK =
      new SafeHttpSender.Options(Duration.ofSeconds(1), Duration.ofSeconds(5), 1024);

  @TempDir static Path dir;
  private static SSLContext serverTls;
  private static SSLContext clientTls;

  private final List<Server> servers = new CopyOnWriteArrayList<>();

  @BeforeAll
  static void certificate() throws Exception {
    Path store = dir.resolve("hooks.p12");
    Process keytool =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair",
                "-alias",
                "hooks",
                "-keyalg",
                "EC",
                "-groupname",
                "secp256r1",
                "-dname",
                "CN=hooks.test",
                "-ext",
                "SAN=dns:hooks.test",
                "-validity",
                "2",
                "-storetype",
                "PKCS12",
                "-keystore",
                store.toString(),
                "-storepass",
                "changeit",
                "-keypass",
                "changeit")
            .redirectErrorStream(true)
            .start();
    String out = new String(keytool.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(keytool.waitFor(60, TimeUnit.SECONDS)).isTrue();
    assertThat(keytool.exitValue()).as(out).isZero();

    KeyStore ks = KeyStore.getInstance("PKCS12");
    try (InputStream in = Files.newInputStream(store)) {
      ks.load(in, "changeit".toCharArray());
    }
    KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    kmf.init(ks, "changeit".toCharArray());
    serverTls = SSLContext.getInstance("TLS");
    serverTls.init(kmf.getKeyManagers(), null, null);
    TrustManagerFactory tmf =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    tmf.init(ks);
    clientTls = SSLContext.getInstance("TLS");
    clientTls.init(null, tmf.getTrustManagers(), null);
  }

  @AfterEach
  void stopServers() {
    servers.forEach(Server::close);
  }

  // ---------------------------------------------------------------------------------------------

  /** What a test server does with a connection once it has read the request. */
  @FunctionalInterface
  interface Reply {
    void write(OutputStream out) throws Exception;
  }

  /** A one-port HTTP/1.1 server that records what it was sent and answers with {@code reply}. */
  final class Server implements AutoCloseable {
    final ServerSocket socket;
    final AtomicInteger connections = new AtomicInteger();
    final List<String> requests = new CopyOnWriteArrayList<>();
    final List<List<SNIServerName>> serverNames = new CopyOnWriteArrayList<>();
    private final Thread acceptor;

    Server(boolean tls, Reply reply) throws IOException {
      socket =
          tls
              ? serverTls.getServerSocketFactory().createServerSocket(0, 50, LOOPBACK)
              : new ServerSocket(0, 50, LOOPBACK);
      acceptor =
          Thread.ofVirtual()
              .start(
                  () -> {
                    while (!socket.isClosed()) {
                      try {
                        Socket s = socket.accept();
                        connections.incrementAndGet();
                        Thread.ofVirtual().start(() -> handle(s, reply));
                      } catch (IOException e) {
                        return;
                      }
                    }
                  });
      servers.add(this);
    }

    int port() {
      return socket.getLocalPort();
    }

    private void handle(Socket s, Reply reply) {
      try (s) {
        if (s instanceof SSLSocket ssl) {
          ssl.startHandshake();
          serverNames.add(((ExtendedSSLSession) ssl.getSession()).getRequestedServerNames());
        }
        requests.add(readRequest(s.getInputStream()));
        OutputStream out = s.getOutputStream();
        reply.write(out);
        out.flush();
      } catch (Exception e) {
        // The client hung up, as it does on purpose in several tests.
      }
    }

    @Override
    public void close() {
      try {
        socket.close();
      } catch (IOException ignored) {
        // already closed
      }
      acceptor.interrupt();
    }
  }

  private static String readRequest(InputStream in) throws IOException {
    ByteArrayOutputStream head = new ByteArrayOutputStream();
    int c;
    while ((c = in.read()) >= 0) {
      head.write(c);
      byte[] b = head.toByteArray();
      int n = b.length;
      if (n >= 4 && b[n - 4] == '\r' && b[n - 3] == '\n' && b[n - 2] == '\r' && b[n - 1] == '\n') {
        break;
      }
    }
    String h = head.toString(StandardCharsets.ISO_8859_1);
    int length = 0;
    for (String line : h.lines().toList()) {
      if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
        length = Integer.parseInt(line.substring(15).strip());
      }
    }
    return h + new String(in.readNBytes(length), StandardCharsets.UTF_8);
  }

  private static Reply text(String response) {
    return out -> out.write(response.getBytes(StandardCharsets.ISO_8859_1));
  }

  private static Reply ok(String body) {
    return text("HTTP/1.1 200 OK\r\nContent-Length: " + body.length() + "\r\n\r\n" + body);
  }

  /** A resolver that answers from a fixed list and counts its lookups. */
  static final class Dns implements SafeHttpSender.Resolver {
    final AtomicInteger lookups = new AtomicInteger();
    private final List<List<InetAddress>> answers;

    Dns(List<List<InetAddress>> answers) {
      this.answers = answers;
    }

    static Dns always(InetAddress... a) {
      return new Dns(List.of(List.of(a)));
    }

    @Override
    public List<InetAddress> resolve(String host) throws UnknownHostException {
      int n = lookups.getAndIncrement();
      if (!host.endsWith(".test")) {
        throw new UnknownHostException(host);
      }
      return answers.get(Math.min(n, answers.size() - 1));
    }
  }

  private static SafeHttpSender sender(SafeHttpSender.Resolver dns, SafeHttpSender.Options o) {
    return new SafeHttpSender(POLICY, dns, clientTls, o, "payflow-webhooks/test");
  }

  private static SafeHttpSender sender(SafeHttpSender.Resolver dns) {
    return sender(dns, QUICK);
  }

  private static Answered answered(Outcome o) {
    assertThat(o).isInstanceOf(Answered.class);
    return (Answered) o;
  }

  private static Failed failed(Outcome o, Failure f) {
    assertThat(o).isInstanceOf(Failed.class);
    Failed failed = (Failed) o;
    assertThat(failed.failure()).as(failed.describe()).isEqualTo(f);
    return failed;
  }

  // ---------------------------------------------------------------------------------------------

  @Test
  void postsTheBodyToTheCheckedAddressWithTheUrlsOwnHost() throws Exception {
    Server s = new Server(false, ok("thanks"));
    Dns dns = Dns.always(LOOPBACK);
    Outcome o =
        sender(dns)
            .post(
                "http://hooks.test:" + s.port() + "/in/payflow?src=a%20b",
                Map.of("Payflow-Signature", "t=1,v1=ab"),
                PAYLOAD);

    Answered a = answered(o);
    assertThat(a.status()).isEqualTo(200);
    assertThat(a.isSuccess()).isTrue();
    assertThat(a.body()).isEqualTo("thanks");
    assertThat(a.truncated()).isFalse();
    assertThat(a.address()).isEqualTo(LOOPBACK);
    assertThat(dns.lookups).hasValue(1);
    String req = s.requests.getFirst();
    assertThat(req)
        .startsWith("POST /in/payflow?src=a%20b HTTP/1.1\r\nHost: hooks.test:" + s.port() + "\r\n")
        .contains("\r\nUser-Agent: payflow-webhooks/test\r\n")
        .contains("\r\nContent-Type: application/json\r\n")
        .contains("\r\nPayflow-Signature: t=1,v1=ab\r\n")
        .contains("\r\nContent-Length: " + PAYLOAD.length + "\r\n")
        .contains("\r\nConnection: close\r\n")
        .endsWith("\r\n\r\n{\"type\":\"order.created\"}");
  }

  @Test
  void resolvesOnceSoARebindingNameCannotSwapTheAddressAfterTheCheck() throws Exception {
    Server s = new Server(false, ok(""));
    // The first answer is the allowed address; every later one is private. A sender that
    // looked up again to connect would reach 10.0.0.1 after checking 127.0.0.1.
    Dns dns = new Dns(List.of(List.of(LOOPBACK), List.of(InetAddress.ofLiteral("10.0.0.1"))));
    SafeHttpSender sender = sender(dns);
    String url = "http://rebind.test:" + s.port() + "/";

    assertThat(answered(sender.post(url, Map.of(), PAYLOAD)).status()).isEqualTo(200);
    assertThat(dns.lookups).as("one lookup for the check and the connection").hasValue(1);

    // The next send looks up afresh, gets the private answer and goes nowhere.
    Failed f = failed(sender.post(url, Map.of(), PAYLOAD), Failure.ADDRESS_REFUSED);
    assertThat(f.detail())
        .isEqualTo(
            "rebind.test resolves to 10.0.0.1, a private address; webhooks go to public"
                + " addresses only");
    assertThat(dns.lookups).hasValue(2);
    assertThat(s.connections).hasValue(1);
  }

  @Test
  void oneRefusedAddressAmongSeveralRefusesTheSend() throws Exception {
    Server s = new Server(false, ok(""));
    for (InetAddress bad :
        List.of(
            InetAddress.ofLiteral("169.254.169.254"),
            InetAddress.ofLiteral("127.0.0.2"),
            InetAddress.ofLiteral("::1"),
            InetAddress.ofLiteral("100.100.100.100"))) {
      Dns dns = Dns.always(LOOPBACK, bad);
      Failed f =
          failed(
              sender(dns).post("http://mixed.test:" + s.port() + "/", Map.of(), PAYLOAD),
              Failure.ADDRESS_REFUSED);
      assertThat(f.detail()).contains("mixed.test resolves to " + bad.getHostAddress());
    }
    assertThat(s.connections).as("not one connection was made").hasValue(0);
  }

  @Test
  void anIpv4AddressInsideAnIpv6OneIsCheckedAsTheIpv4Address() throws Exception {
    byte[] mapped = new byte[16];
    mapped[10] = (byte) 0xff;
    mapped[11] = (byte) 0xff;
    mapped[12] = (byte) 169;
    mapped[13] = (byte) 254;
    mapped[14] = (byte) 169;
    mapped[15] = (byte) 254;
    InetAddress v6 = java.net.Inet6Address.getByAddress(null, mapped, -1);
    Failed f =
        failed(
            sender(Dns.always(v6)).post("http://meta.test/", Map.of(), PAYLOAD),
            Failure.ADDRESS_REFUSED);
    assertThat(f.detail()).contains("wrapping 169.254.169.254, the cloud metadata address");

    for (String literal :
        List.of(
            "http://[::ffff:169.254.169.254]/",
            "http://[::ffff:a9fe:a9fe]/",
            "http://[64:ff9b::a9fe:a9fe]/",
            "http://[2002:a9fe:a9fe::]/",
            "http://[::1]/",
            "http://127.0.0.2/")) {
      Dns dns = Dns.always(LOOPBACK);
      failed(sender(dns).post(literal, Map.of(), PAYLOAD), Failure.ADDRESS_REFUSED);
      assertThat(dns.lookups).as("a literal is never looked up").hasValue(0);
    }
  }

  @Test
  void redirectsAreAnswersNotFollowed() throws Exception {
    Server target = new Server(false, ok("you found me"));
    Server s =
        new Server(
            false,
            text(
                "HTTP/1.1 307 Temporary Redirect\r\nLocation: http://127.0.0.1:"
                    + target.port()
                    + "/\r\nContent-Length: 0\r\n\r\n"));
    Answered a =
        answered(
            sender(Dns.always(LOOPBACK)).post("http://hooks.test:" + s.port(), Map.of(), PAYLOAD));
    assertThat(a.status()).isEqualTo(307);
    assertThat(a.isSuccess()).isFalse();
    assertThat(target.connections).hasValue(0);
    assertThat(s.requests.getFirst()).startsWith("POST / HTTP/1.1\r\n");
  }

  @Test
  void aSlowBodyIsCutOffAtTheDeadlineAndTheStatusStands() throws Exception {
    Server s =
        new Server(
            false,
            out -> {
              out.write(
                  "HTTP/1.1 200 OK\r\nContent-Length: 100000\r\n\r\n"
                      .getBytes(StandardCharsets.US_ASCII));
              out.flush();
              for (int i = 0; i < 1000; i++) {
                out.write('x');
                out.flush();
                Thread.sleep(50);
              }
            });
    SafeHttpSender sender =
        sender(
            Dns.always(LOOPBACK),
            new SafeHttpSender.Options(Duration.ofSeconds(1), Duration.ofMillis(800), 1024));
    long start = System.nanoTime();
    Answered a = answered(sender.post("http://hooks.test:" + s.port(), Map.of(), PAYLOAD));
    long took = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    assertThat(a.status()).isEqualTo(200);
    assertThat(a.truncated()).isTrue();
    assertThat(a.body()).matches("x{1,20}");
    assertThat(took).isBetween(700L, 2_000L);
  }

  @Test
  void aServerThatTricklesItsHeadOrSaysNothingTimesOut() throws Exception {
    Server silent = new Server(false, out -> Thread.sleep(10_000));
    Server trickle =
        new Server(
            false,
            out -> {
              for (byte b :
                  "HTTP/1.1 200 OK\r\nX-Slow: yes\r\n\r\n".getBytes(StandardCharsets.US_ASCII)) {
                out.write(b);
                out.flush();
                Thread.sleep(100);
              }
            });
    SafeHttpSender sender =
        sender(
            Dns.always(LOOPBACK),
            new SafeHttpSender.Options(Duration.ofSeconds(1), Duration.ofMillis(800), 1024));
    for (Server s : List.of(silent, trickle)) {
      long start = System.nanoTime();
      Failed f =
          failed(sender.post("http://hooks.test:" + s.port(), Map.of(), PAYLOAD), Failure.TIMEOUT);
      long took = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
      assertThat(took).isBetween(700L, 2_000L);
      assertThat(f.latency()).isGreaterThanOrEqualTo(Duration.ofMillis(700));
      assertThat(f.describe()).isEqualTo("timeout: no answer in 800 ms");
    }
  }

  @Test
  void aSlowLookupTimesOutWithinTheDeadline() {
    SafeHttpSender.Resolver slow =
        host -> {
          try {
            Thread.sleep(5_000);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          return List.of(LOOPBACK);
        };
    SafeHttpSender sender =
        sender(
            slow, new SafeHttpSender.Options(Duration.ofSeconds(1), Duration.ofMillis(500), 1024));
    long start = System.nanoTime();
    Failed f = failed(sender.post("http://slow.test/", Map.of(), PAYLOAD), Failure.TIMEOUT);
    assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(2_000L);
    assertThat(f.detail()).isEqualTo("looking up slow.test took too long");
    assertThat(sender.refusal("http://slow.test/")).as("registration can't tell yet").isEmpty();
  }

  @Test
  void anOversizedBodyIsReadOnlyUpToTheCap() throws Exception {
    byte[] big = "y".repeat(4 * 1024 * 1024).getBytes(StandardCharsets.US_ASCII);
    Server lengthKnown =
        new Server(
            false,
            out -> {
              out.write(
                  ("HTTP/1.1 200 OK\r\nContent-Length: " + big.length + "\r\n\r\n")
                      .getBytes(StandardCharsets.US_ASCII));
              out.write(big);
            });
    Server untilClose =
        new Server(
            false,
            out -> {
              out.write("HTTP/1.1 500 Oops\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
              out.write(big);
            });
    Server chunked =
        new Server(
            false,
            out -> {
              out.write(
                  "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
                      .getBytes(StandardCharsets.US_ASCII));
              for (int i = 0; i < 1000; i++) {
                out.write("1000\r\n".getBytes(StandardCharsets.US_ASCII));
                out.write(big, 0, 0x1000);
                out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
              }
              out.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            });
    SafeHttpSender sender = sender(Dns.always(LOOPBACK));
    for (Server s : List.of(lengthKnown, untilClose, chunked)) {
      long start = System.nanoTime();
      Answered a = answered(sender.post("http://hooks.test:" + s.port(), Map.of(), PAYLOAD));
      assertThat(a.body()).hasSize(1024).matches("y+");
      assertThat(a.truncated()).isTrue();
      assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start))
          .as("it hangs up instead of reading 4 MiB")
          .isLessThan(2_000L);
    }
  }

  @Test
  void bodiesAreDecodedAndInterimAnswersSkipped() throws Exception {
    Server chunked =
        new Server(
            false,
            text(
                String.join(
                    "\r\n",
                    "HTTP/1.1 100 Continue",
                    "",
                    "HTTP/1.1 202 Accepted",
                    "Transfer-Encoding: chunked",
                    "",
                    "5;ext=1",
                    "hello",
                    "1",
                    " ",
                    "5",
                    "world",
                    "0",
                    "",
                    "")));
    Server noLength = new Server(false, text("HTTP/1.0 200 OK\r\n\r\nall of it"));
    Server noContent =
        new Server(false, text("HTTP/1.1 204 No Content\r\nContent-Length: 5\r\n\r\n"));
    SafeHttpSender sender = sender(Dns.always(LOOPBACK));

    Answered a = answered(sender.post("http://hooks.test:" + chunked.port(), Map.of(), PAYLOAD));
    assertThat(a.status()).isEqualTo(202);
    assertThat(a.body()).isEqualTo("hello world");
    assertThat(a.truncated()).isFalse();

    Answered b = answered(sender.post("http://hooks.test:" + noLength.port(), Map.of(), PAYLOAD));
    assertThat(b.body()).isEqualTo("all of it");
    assertThat(b.truncated()).isFalse();

    Answered c = answered(sender.post("http://hooks.test:" + noContent.port(), Map.of(), PAYLOAD));
    assertThat(c.status()).isEqualTo(204);
    assertThat(c.body()).isEmpty();
  }

  @Test
  void whatIsNotHttpOrIsTooLargeIsAProtocolFailure() throws Exception {
    Server notHttp = new Server(false, text("SSH-2.0-OpenSSH_9.9\r\n"));
    Server hugeHead =
        new Server(
            false,
            text("HTTP/1.1 200 OK\r\nX-Big: " + "h".repeat(SafeHttpSender.MAX_HEAD) + "\r\n\r\n"));
    Server hangsUp = new Server(false, out -> {});
    SafeHttpSender sender = sender(Dns.always(LOOPBACK));
    failed(sender.post("http://hooks.test:" + notHttp.port(), Map.of(), PAYLOAD), Failure.PROTOCOL);
    assertThat(
            failed(
                    sender.post("http://hooks.test:" + hugeHead.port(), Map.of(), PAYLOAD),
                    Failure.PROTOCOL)
                .detail())
        .isEqualTo("response head over 16384 bytes");
    assertThat(
            failed(
                    sender.post("http://hooks.test:" + hangsUp.port(), Map.of(), PAYLOAD),
                    Failure.IO)
                .detail())
        .isEqualTo("connection closed before a status line");
  }

  @Test
  void noAddressOrNoListenerIsReported() throws Exception {
    SafeHttpSender sender = sender(Dns.always(LOOPBACK));
    Failed dns = failed(sender.post("http://hooks.invalid/", Map.of(), PAYLOAD), Failure.DNS);
    assertThat(dns.describe()).isEqualTo("dns: no address for hooks.invalid");
    assertThat(
            failed(
                    sender(new Dns(List.of(List.of())))
                        .post("http://empty.test/", Map.of(), PAYLOAD),
                    Failure.DNS)
                .detail())
        .isEqualTo("no address for empty.test");

    int closed;
    try (ServerSocket s = new ServerSocket(0, 1, LOOPBACK)) {
      closed = s.getLocalPort();
    }
    Failed refused =
        failed(
            sender.post("http://hooks.test:" + closed + "/", Map.of(), PAYLOAD), Failure.CONNECT);
    assertThat(refused.detail()).startsWith("127.0.0.1: ");
  }

  @Test
  void tlsCarriesTheHostInSniAndVerifiesTheCertificateAgainstIt() throws Exception {
    Server s = new Server(true, ok("secure"));
    Answered a =
        answered(
            sender(Dns.always(LOOPBACK))
                .post("https://hooks.test:" + s.port() + "/tls", Map.of(), PAYLOAD));
    assertThat(a.status()).isEqualTo(200);
    assertThat(a.body()).isEqualTo("secure");
    assertThat(s.serverNames.getFirst()).containsExactly(new SNIHostName("hooks.test"));
    assertThat(s.requests.getFirst()).contains("\r\nHost: hooks.test:" + s.port() + "\r\n");

    // Another name on the same address gets the same certificate, which doesn't name it.
    Failed f =
        failed(
            sender(Dns.always(LOOPBACK))
                .post("https://other.test:" + s.port() + "/tls", Map.of(), PAYLOAD),
            Failure.TLS);
    assertThat(f.detail()).containsIgnoringCase("other.test");
    // So does the address itself: a certificate for a name is no good for an IP literal.
    SafeHttpSender literal =
        new SafeHttpSender(POLICY, Dns.always(LOOPBACK), clientTls, QUICK, "payflow-webhooks/test");
    failed(literal.post("https://127.0.0.1:" + s.port() + "/tls", Map.of(), PAYLOAD), Failure.TLS);
    assertThat(s.requests).as("no request went over an unverified connection").hasSize(1);

    // Plain HTTP to a TLS port is no answer either: the server's alert isn't a status line.
    assertThat(
            sender(Dns.always(LOOPBACK)).post("http://hooks.test:" + s.port(), Map.of(), PAYLOAD))
        .isInstanceOf(Failed.class);
  }

  @Test
  void callersCannotSetFramingHeadersOrSmuggleLines() {
    SafeHttpSender sender = sender(Dns.always(LOOPBACK));
    for (Map<String, String> bad :
        List.of(
            Map.of("Host", "evil.test"),
            Map.of("content-length", "0"),
            Map.of("Transfer-Encoding", "chunked"),
            Map.of("Connection", "keep-alive"),
            Map.of("X-Ok", "a\r\nHost: evil.test"),
            Map.of("X-Ok", "a\nb"),
            Map.of("Bad Name", "x"),
            Map.of("", "x"))) {
      assertThatThrownBy(() -> sender.post("http://hooks.test/", bad, PAYLOAD))
          .as(bad.toString())
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void registrationRefusesWhatASendWouldRefuse() {
    SafeHttpSender strict =
        new SafeHttpSender(
            AddressPolicy.strict(),
            Dns.always(InetAddress.ofLiteral("93.184.215.14"), InetAddress.ofLiteral("10.9.9.9")),
            clientTls,
            QUICK,
            "payflow-webhooks/test");
    assertThat(strict.refusal("https://split.test/in"))
        .contains(
            "split.test resolves to 10.9.9.9, a private address; webhooks go to public addresses"
                + " only");
    assertThat(strict.refusal("http://169.254.169.254/latest"))
        .contains(
            "169.254.169.254 is the cloud metadata address; webhooks go to public addresses only");
    assertThat(strict.refusal("http://[::ffff:7f00:1]:8080/"))
        .contains("::ffff:7f00:1 is a loopback address; webhooks go to public addresses only");
    assertThat(strict.refusal("https://nowhere.invalid/")).as("doesn't resolve yet").isEmpty();
    assertThat(
            new SafeHttpSender(
                    AddressPolicy.strict(),
                    Dns.always(InetAddress.ofLiteral("93.184.215.14")),
                    clientTls,
                    QUICK,
                    "payflow-webhooks/test")
                .refusal("https://public.test/in"))
        .isEmpty();
  }

  @Test
  void failuresDescribeThemselvesWithinTheLogColumn() {
    Failed f = new Failed(Failure.CONNECT, "z".repeat(600), Duration.ZERO);
    assertThat(f.describe()).hasSize(512).startsWith("connect: zzz").endsWith("...");
    assertThatThrownBy(() -> new SafeHttpSender.Options(Duration.ZERO, Duration.ofSeconds(1), 1024))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new SafeHttpSender.Options(Duration.ofSeconds(1), Duration.ofSeconds(1), -1))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
