package io.github.laiwf94.payflow.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.laiwf94.payflow.webhook.SafeHttpSender.Answered;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs the code in docs/webhooks.md, so a receiver written from that page accepts what payflow
 * sends and refuses what it shouldn't. Each verification function (Python, Node.js, Java) is taken
 * from the page as it stands and run against deliveries that {@link WebhookSignature} signs and
 * {@link SafeHttpSender} sends over a real socket, and the page's complete receiver is run as a
 * server and sent real deliveries. {@link WebhookSignature#verify} must agree on every case.
 */
class WebhookDocsTest {

  private static final Path DOC = Path.of("docs", "webhooks.md");
  private static final SecureRandom RANDOM = new SecureRandom();

  @TempDir static Path dir;

  private static String doc;
  private static List<Case> cases;

  /**
   * A header and body as a receiver gets them, a secret it holds, its clock and the verdict. Never
   * compared or hashed, so the array's identity equality doesn't matter.
   */
  @SuppressWarnings("ArrayRecordComponent")
  record Case(String label, String header, byte[] body, String secret, long now, boolean valid) {}

  /** What a loopback server read off the wire. */
  @SuppressWarnings("ArrayRecordComponent")
  record Received(String signature, byte[] body) {}

  @BeforeAll
  static void deliveries() throws Exception {
    doc = Files.readString(DOC, StandardCharsets.UTF_8);
    String a = secret();
    String b = secret();
    String c = secret();
    long t = Instant.now().getEpochSecond();

    byte[] event =
        ("{\"event_id\":\""
                + UUID.randomUUID()
                + "\",\"event_type\":\"order.completed\",\"order_id\":\""
                + UUID.randomUUID()
                + "\",\"merchant_id\":\"acme\",\"merchant_reference\":\"Grüße ✓\",\"seq\":5,"
                + "\"state\":\"COMPLETED\",\"amount\":4200,\"currency\":\"EUR\"}")
            .getBytes(StandardCharsets.UTF_8);
    // Bytes that aren't UTF-8 at all: a receiver must sign-check bytes, not decoded text.
    byte[] binary = concat(event, new byte[] {(byte) 0xff, (byte) 0xfe, 0x00, 0x7f});

    // Every signed body goes through the real sender to a real socket, and the cases use what
    // arrived there, so the receivers are checked against the bytes payflow puts on the wire.
    Received one = send(WebhookSignature.header(t, event, List.of(a)), event);
    Received rotating = send(WebhookSignature.header(t, event, List.of(b, a)), event);
    Received raw = send(WebhookSignature.header(t, binary, List.of(a)), binary);
    assertThat(one.body()).isEqualTo(event);
    assertThat(raw.body()).isEqualTo(binary);

    String sig = one.signature();
    String v1 = sig.substring(sig.indexOf(",v1=") + 4);
    byte[] flipped = one.body().clone();
    flipped[flipped.length / 2] ^= 0x01;
    byte[] reformatted =
        new String(one.body(), StandardCharsets.UTF_8)
            .replace(",\"", ", \"")
            .getBytes(StandardCharsets.UTF_8);
    byte[] decoded =
        new String(raw.body(), StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8);

    cases =
        List.of(
            new Case("a delivery as sent", sig, one.body(), a, t, true),
            new Case("five minutes late, the limit", sig, one.body(), a, t + 300, true),
            new Case("five minutes and a second late", sig, one.body(), a, t + 301, false),
            new Case("over five minutes early", sig, one.body(), a, t - 301, false),
            new Case("another endpoint's secret", sig, one.body(), c, t, false),
            new Case("one bit of the body flipped", sig, flipped, a, t, false),
            new Case("the JSON parsed and written again", sig, reformatted, a, t, false),
            new Case("rotating: the new secret", rotating.signature(), event, b, t, true),
            new Case("rotating: the old secret", rotating.signature(), event, a, t, true),
            new Case("rotating: neither secret", rotating.signature(), event, c, t, false),
            new Case("the timestamp moved", "t=" + (t - 1) + ",v1=" + v1, event, a, t, false),
            new Case("no timestamp", "v1=" + v1, event, a, t, false),
            new Case("no signature", "t=" + t, event, a, t, false),
            new Case("two timestamps", "t=" + t + ",t=" + t + ",v1=" + v1, event, a, t, false),
            new Case("a signed timestamp", "t=+" + t + ",v1=" + v1, event, a, t, false),
            new Case("an empty header", "", event, a, t, false),
            new Case("not key=value pairs", "garbage", event, a, t, false),
            new Case(
                "an unknown scheme beside v1 is ignored",
                "t=" + t + ",v0=deadbeef,v1=" + v1,
                event,
                a,
                t,
                true),
            new Case("bytes that aren't UTF-8", raw.signature(), raw.body(), a, t, true),
            new Case("the same bytes decoded and re-encoded", raw.signature(), decoded, a, t, false),
            new Case("another body's signature", sig, raw.body(), a, t, false));

    for (Case k : cases) {
      assertThat(
              WebhookSignature.verify(
                  k.header(), k.body(), k.secret(), k.now(), WebhookSignature.DEFAULT_TOLERANCE))
          .as("payflow's own verifier: %s", k.label())
          .isEqualTo(k.valid());
    }
  }

  // ---------------------------------------------------------------------------------------------

  @Test
  void thePythonVerifierOnThePageAcceptsExactlyTheValidDeliveries() throws Exception {
    String harness =
        snippet("verify-python", "python")
            + "\n\nimport base64, json, sys\n"
            + "for case in json.load(sys.stdin):\n"
            + "    body = base64.b64decode(case['body'])\n"
            + "    ok = verify(case['header'], body, case['secret'], now=case['now'])\n"
            + "    print('true' if ok is True else 'false' if ok is False else repr(ok))\n";
    Path script = dir.resolve("verify.py");
    Files.writeString(script, harness, StandardCharsets.UTF_8);
    assertVerdicts(run(List.of("python3", script.toString()), casesJson()));
  }

  @Test
  void theNodeVerifierOnThePageAcceptsExactlyTheValidDeliveries() throws Exception {
    String harness =
        snippet("verify-node", "js")
            + "\n\nconst input = require('node:fs').readFileSync(0, 'utf8');\n"
            + "for (const c of JSON.parse(input)) {\n"
            + "  const ok = verify(c.header, Buffer.from(c.body, 'base64'), c.secret, c.now);\n"
            + "  console.log(ok === true ? 'true' : ok === false ? 'false' : String(ok));\n"
            + "}\n";
    Path script = dir.resolve("verify.js");
    Files.writeString(script, harness, StandardCharsets.UTF_8);
    assertVerdicts(run(List.of("node", script.toString()), casesJson()));
  }

  @Test
  void theJavaVerifierOnThePageCompilesForJava17AndAcceptsExactlyTheValidDeliveries()
      throws Exception {
    Path src = dir.resolve("java-src");
    Path out = dir.resolve("java-out");
    Files.createDirectories(src);
    Files.createDirectories(out);
    Path file = src.resolve("PayflowWebhooks.java");
    Files.writeString(file, snippet("verify-java", "java"), StandardCharsets.UTF_8);
    JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
    ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
    int status =
        javac.run(
            null,
            diagnostics,
            diagnostics,
            "--release",
            "17",
            "-Xlint:all",
            "-Werror",
            "-d",
            out.toString(),
            file.toString());
    assertThat(status).as(diagnostics.toString(StandardCharsets.UTF_8)).isZero();

    try (URLClassLoader loader =
        new URLClassLoader(new URL[] {out.toUri().toURL()}, getClass().getClassLoader())) {
      Method verify =
          loader
              .loadClass("PayflowWebhooks")
              .getMethod("verify", String.class, byte[].class, String.class, long.class);
      List<String> verdicts = new ArrayList<>();
      for (Case k : cases) {
        verdicts.add(
            String.valueOf(verify.invoke(null, k.header(), k.body(), k.secret(), k.now())));
      }
      assertVerdicts(verdicts);
    }
  }

  @Test
  void theReceiverOnThePageHandlesEachEventOnceInOrderAndRefusesForgeries() throws Exception {
    Path script = dir.resolve("receiver.py");
    Files.writeString(script, snippet("receiver-python", "python"), StandardCharsets.UTF_8);
    String a = secret();
    String b = secret();
    ProcessBuilder pb =
        new ProcessBuilder("python3", script.toString()).redirectErrorStream(true);
    pb.environment().put("PAYFLOW_WEBHOOK_SECRETS", a);
    pb.environment().put("PORT", "0");
    Process p = pb.start();
    BlockingQueue<String> lines = new LinkedBlockingQueue<>();
    Thread.ofVirtual().start(() -> pump(p.getInputStream(), lines));
    try {
      String first = lines.poll(30, TimeUnit.SECONDS);
      assertThat(first).as("the receiver starts").isNotNull().startsWith("listening on ");
      String url = "http://127.0.0.1:" + first.substring("listening on ".length()).strip() + "/";
      UUID order = UUID.randomUUID();
      long now = Instant.now().getEpochSecond();

      Event created = Event.of("order.created", order, 1);
      Event authorized = Event.of("order.payment_authorized", order, 2);
      Event stale = Event.of("order.inventory_reserved", order, 1);
      Event ping =
          new Event(
              UUID.randomUUID(),
              "ping",
              ("{\"event_type\":\"ping\",\"merchant_id\":\"acme\",\"event_id\":\"")
                  .getBytes(StandardCharsets.UTF_8));

      assertThat(deliver(url, created, now, List.of(a), 1)).as("a delivery").isEqualTo(204);
      assertThat(deliver(url, created, now + 1, List.of(a), 2))
          .as("the same event again, freshly signed: acknowledged, not handled again")
          .isEqualTo(200);
      assertThat(deliver(url, authorized, now, List.of(secret()), 1))
          .as("signed with a secret the receiver doesn't hold")
          .isEqualTo(400);
      assertThat(deliverTampered(url, authorized, now, a))
          .as("a body changed after signing")
          .isEqualTo(400);
      assertThat(deliver(url, authorized, now - 600, List.of(a), 1))
          .as("a delivery captured ten minutes ago and sent again")
          .isEqualTo(400);
      assertThat(deliver(url, authorized, now, List.of(b, a), 1))
          .as("mid-rotation: the new secret, which the receiver doesn't have yet, and the old")
          .isEqualTo(204);
      assertThat(deliver(url, stale, now, List.of(a), 1))
          .as("an older event of the order, as a replay could bring")
          .isEqualTo(200);
      assertThat(deliver(url, ping, now, List.of(a), 1)).as("a ping, which has no order")
          .isEqualTo(204);

      List<String> handled = new ArrayList<>();
      String line;
      while (handled.size() < 3 && (line = lines.poll(10, TimeUnit.SECONDS)) != null) {
        handled.add(line);
      }
      assertThat(lines.poll(300, TimeUnit.MILLISECONDS)).as("nothing more handled").isNull();
      assertThat(handled)
          .containsExactly(
              "handled order.created " + created.id(),
              "handled order.payment_authorized " + authorized.id(),
              "handled ping " + ping.id());
    } finally {
      p.destroy();
      if (!p.waitFor(10, TimeUnit.SECONDS)) {
        p.destroyForcibly();
      }
    }
  }

  @Test
  void thePageNamesEveryEventTypeAndHeaderPayflowSends() {
    for (String type : WebhookEvents.TYPES) {
      assertThat(doc).as(type).contains("`" + type + "`");
    }
    assertThat(doc).contains("`" + WebhookEvents.PING + "`");
    for (String header :
        List.of(
            WebhookSignature.HEADER,
            WebhookDispatcher.HEADER_EVENT_ID,
            WebhookDispatcher.HEADER_EVENT_TYPE,
            WebhookDispatcher.HEADER_DELIVERY_ID,
            WebhookDispatcher.HEADER_ATTEMPT)) {
      assertThat(doc).as(header).contains("| `" + header + "` |");
    }
  }

  // ---------------------------------------------------------------------------------------------

  /** An event the receiver test sends: its id, type and body (completed with the id). */
  @SuppressWarnings("ArrayRecordComponent")
  record Event(UUID id, String type, byte[] prefix) {

    static Event of(String type, UUID order, int seq) {
      return new Event(
          UUID.randomUUID(),
          type,
          ("{\"event_type\":\""
                  + type
                  + "\",\"order_id\":\""
                  + order
                  + "\",\"seq\":"
                  + seq
                  + ",\"merchant_id\":\"acme\",\"event_id\":\"")
              .getBytes(StandardCharsets.UTF_8));
    }

    byte[] body() {
      return concat(prefix, (id + "\"}").getBytes(StandardCharsets.UTF_8));
    }
  }

  private static int deliver(String url, Event e, long t, List<String> secrets, int attempt) {
    byte[] body = e.body();
    return status(url, headers(e, WebhookSignature.header(t, body, secrets), attempt), body);
  }

  private static int deliverTampered(String url, Event e, long t, String secret) {
    byte[] body = e.body();
    String header = WebhookSignature.header(t, body, List.of(secret));
    byte[] changed = new String(body, StandardCharsets.UTF_8).replace("acme", "acmf").getBytes(
        StandardCharsets.UTF_8);
    return status(url, headers(e, header, 1), changed);
  }

  private static Map<String, String> headers(Event e, String signature, int attempt) {
    Map<String, String> h = new LinkedHashMap<>();
    h.put(WebhookSignature.HEADER, signature);
    h.put(WebhookDispatcher.HEADER_EVENT_ID, e.id().toString());
    h.put(WebhookDispatcher.HEADER_EVENT_TYPE, e.type());
    h.put(WebhookDispatcher.HEADER_ATTEMPT, Integer.toString(attempt));
    return h;
  }

  private static int status(String url, Map<String, String> headers, byte[] body) {
    SafeHttpSender.Outcome o = sender().post(url, headers, body);
    assertThat(o).isInstanceOf(Answered.class);
    return ((Answered) o).status();
  }

  private static SafeHttpSender sender() {
    try {
      return new SafeHttpSender(
          AddressPolicy.allowing(List.of("127.0.0.1/32")),
          SafeHttpSender.Resolver.SYSTEM,
          SSLContext.getDefault(),
          new SafeHttpSender.Options(Duration.ofSeconds(2), Duration.ofSeconds(10), 1024),
          "payflow-webhooks/test");
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Sends one signed body through {@link SafeHttpSender} and returns what the server read. */
  private static Received send(String signature, byte[] body) throws Exception {
    try (ServerSocket server = new ServerSocket(0, 1, InetAddress.ofLiteral("127.0.0.1"))) {
      BlockingQueue<Received> got = new LinkedBlockingQueue<>();
      Thread.ofVirtual()
          .start(
              () -> {
                try (Socket s = server.accept()) {
                  got.add(read(s.getInputStream()));
                  OutputStream out = s.getOutputStream();
                  out.write(
                      "HTTP/1.1 204 No Content\r\nContent-Length: 0\r\n\r\n"
                          .getBytes(StandardCharsets.US_ASCII));
                  out.flush();
                } catch (IOException e) {
                  // the assertion on the outcome below reports it
                }
              });
      Answered a =
          (Answered)
              sender()
                  .post(
                      "http://127.0.0.1:" + server.getLocalPort() + "/hooks",
                      Map.of(WebhookSignature.HEADER, signature),
                      body);
      assertThat(a.status()).isEqualTo(204);
      Received r = got.poll(10, TimeUnit.SECONDS);
      assertThat(r).isNotNull();
      assertThat(r.signature()).isEqualTo(signature);
      return r;
    }
  }

  private static Received read(InputStream in) throws IOException {
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
    int length = 0;
    String signature = null;
    for (String line : head.toString(StandardCharsets.ISO_8859_1).lines().toList()) {
      int colon = line.indexOf(':');
      if (colon < 0) {
        continue;
      }
      String name = line.substring(0, colon).strip().toLowerCase(Locale.ROOT);
      String value = line.substring(colon + 1).strip();
      if (name.equals("content-length")) {
        length = Integer.parseInt(value);
      } else if (name.equals(WebhookSignature.HEADER.toLowerCase(Locale.ROOT))) {
        signature = value;
      }
    }
    return new Received(signature, in.readNBytes(length));
  }

  /** The fenced block of {@code lang} right after the page's {@code docs-test:name} marker. */
  private static String snippet(String name, String lang) {
    Matcher m =
        Pattern.compile(
                "<!-- docs-test:" + Pattern.quote(name) + " -->\\n```" + lang + "\\n(.*?)\\n```\\n",
                Pattern.DOTALL)
            .matcher(doc);
    assertThat(m.find()).as("docs/webhooks.md has a %s block marked %s", lang, name).isTrue();
    String code = m.group(1);
    assertThat(m.find()).as("one block marked %s", name).isFalse();
    return code + "\n";
  }

  private static String casesJson() {
    StringBuilder json = new StringBuilder("[");
    for (Case k : cases) {
      if (json.length() > 1) {
        json.append(',');
      }
      json.append("{\"header\":")
          .append(quote(k.header()))
          .append(",\"body\":\"")
          .append(Base64.getEncoder().encodeToString(k.body()))
          .append("\",\"secret\":")
          .append(quote(k.secret()))
          .append(",\"now\":")
          .append(k.now())
          .append('}');
    }
    return json.append(']').toString();
  }

  private static String quote(String s) {
    assertThat(s).matches("[\\x20-\\x7e]*");
    return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }

  private static List<String> run(List<String> command, String stdin) throws Exception {
    Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
    try (OutputStream in = p.getOutputStream()) {
      in.write(stdin.getBytes(StandardCharsets.UTF_8));
    }
    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(p.waitFor(60, TimeUnit.SECONDS)).as(command.getFirst() + " finishes").isTrue();
    assertThat(p.exitValue()).as(out).isZero();
    return out.lines().toList();
  }

  private static void assertVerdicts(List<String> verdicts) {
    assertThat(verdicts).hasSameSizeAs(cases);
    for (int i = 0; i < cases.size(); i++) {
      assertThat(verdicts.get(i)).as(cases.get(i).label()).isEqualTo(
          String.valueOf(cases.get(i).valid()));
    }
  }

  private static void pump(InputStream in, BlockingQueue<String> lines) {
    try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
      String line;
      while ((line = r.readLine()) != null) {
        lines.add(line);
      }
    } catch (IOException e) {
      // the process ended
    }
  }

  private static String secret() {
    byte[] b = new byte[32];
    RANDOM.nextBytes(b);
    return "whsec_" + Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }

  private static byte[] concat(byte[] a, byte[] b) {
    byte[] out = new byte[a.length + b.length];
    System.arraycopy(a, 0, out, 0, a.length);
    System.arraycopy(b, 0, out, a.length, b.length);
    return out;
  }
}
