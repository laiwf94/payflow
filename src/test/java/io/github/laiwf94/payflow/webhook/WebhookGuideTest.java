package io.github.laiwf94.payflow.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.laiwf94.payflow.webhook.SafeHttpSender.Answered;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.net.ssl.SSLContext;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * docs/webhooks.md is held to payflow: its test vector is recomputed, its example body is what
 * Postgres renders, every verification example (Python, Node.js, Java, openssl) gives the same
 * answer as {@link WebhookSignature#verify} on fixed edge cases and on generated requests, and the
 * complete receiver is started and sent signed deliveries by the real sender.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class WebhookGuideTest {

  private static final Path GUIDE = Path.of("docs", "webhooks.md");
  private static final Pattern EXAMPLE =
      Pattern.compile("<!-- example: ([a-z]+) -->\\n```[a-z]*\\n(.*?)\\n```", Pattern.DOTALL);
  private static final Duration TOLERANCE = WebhookSignature.DEFAULT_TOLERANCE;
  private static final Duration PROCESS_LIMIT = Duration.ofSeconds(60);

  private static Map<String, String> examples;

  @TempDir static Path dir;
  @Autowired JdbcClient jdbc;

  @BeforeAll
  static void readGuide() throws IOException {
    String md = Files.readString(GUIDE, StandardCharsets.UTF_8);
    Map<String, String> found = new LinkedHashMap<>();
    Matcher m = EXAMPLE.matcher(md);
    while (m.find()) {
      assertThat(found.put(m.group(1), m.group(2))).as("one %s example", m.group(1)).isNull();
    }
    assertThat(found)
        .containsOnlyKeys("body", "vector", "python", "node", "java", "openssl", "receiver");
    examples = found;
  }

  // ---------------------------------------------------------------------------------------------

  /** One request a receiver is asked about, and the secrets it holds. */
  static final class Case {
    private final String label;
    private final String header;
    private final byte[] body;
    private final List<String> secrets;
    private final long now;

    Case(String label, String header, byte[] body, List<String> secrets, long now) {
      this.label = label;
      this.header = header;
      this.body = body.clone();
      this.secrets = List.copyOf(secrets);
      this.now = now;
    }

    String label() {
      return label;
    }

    String header() {
      return header;
    }

    byte[] body() {
      return body.clone();
    }

    List<String> secrets() {
      return secrets;
    }

    long now() {
      return now;
    }

    /** What payflow's own check says: any held secret verifies it. */
    boolean expected() {
      return secrets.stream()
          .anyMatch(s -> WebhookSignature.verify(header, body, s, now, TOLERANCE));
    }

    /** Tab-separated base64 fields: header, body, now, secrets (comma-separated). */
    String line() {
      Base64.Encoder b = Base64.getEncoder();
      return b.encodeToString(header.getBytes(StandardCharsets.UTF_8))
          + "\t"
          + b.encodeToString(body)
          + "\t"
          + now
          + "\t"
          + secrets.stream()
              .map(s -> b.encodeToString(s.getBytes(StandardCharsets.UTF_8)))
              .collect(Collectors.joining(","));
    }
  }

  private static byte[] body() {
    return examples.get("body").getBytes(StandardCharsets.UTF_8);
  }

  private static Map<String, String> vector() {
    Map<String, String> v = new LinkedHashMap<>();
    for (String line : examples.get("vector").lines().toList()) {
      int colon = line.indexOf(':');
      v.put(line.substring(0, colon).strip(), line.substring(colon + 1).strip());
    }
    return v;
  }

  /** Secrets made for this run, shaped like payflow's: whsec_ and 43 base64url characters. */
  private static List<String> freshSecrets(Random random, int n) {
    List<String> out = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      byte[] b = new byte[32];
      random.nextBytes(b);
      out.add(
          WebhookEndpointService.SECRET_PREFIX
              + Base64.getUrlEncoder().withoutPadding().encodeToString(b));
    }
    return out;
  }

  /** Edge cases with the answer spelled out, then generated requests answered by payflow. */
  private static List<Case> cases() {
    Map<String, String> v = vector();
    String s1 = v.get("secret");
    String s2 = v.get("previous");
    String s3 = WebhookEndpointService.SECRET_PREFIX + "test-only-docs-unrelated-key";
    long t = Long.parseLong(v.get("timestamp"));
    byte[] body = body();
    String one = WebhookSignature.header(t, body, List.of(s1));
    String two = WebhookSignature.header(t, body, List.of(s1, s2));
    String hex = one.substring(one.indexOf("v1=") + 3);
    byte[] extraNewline = (examples.get("body") + "\n").getBytes(StandardCharsets.UTF_8);
    byte[] binary = new byte[256];
    for (int i = 0; i < binary.length; i++) {
      binary[i] = (byte) i;
    }

    List<Case> fixed = new ArrayList<>();
    record Edge(Case c, boolean answer) {}
    List<Edge> edges =
        List.of(
            new Edge(new Case("signed", one, body, List.of(s1), t), true),
            new Edge(new Case("5 minutes late", one, body, List.of(s1), t + 300), true),
            new Edge(new Case("5 minutes early", one, body, List.of(s1), t - 300), true),
            new Edge(new Case("too late", one, body, List.of(s1), t + 301), false),
            new Edge(new Case("too early", one, body, List.of(s1), t - 301), false),
            new Edge(new Case("one byte more", one, extraNewline, List.of(s1), t), false),
            new Edge(new Case("wrong secret", one, body, List.of(s3), t), false),
            new Edge(new Case("rotating, new held", two, body, List.of(s1), t), true),
            new Edge(new Case("rotating, old held", two, body, List.of(s2), t), true),
            new Edge(new Case("rotating, neither", two, body, List.of(s3), t), false),
            new Edge(new Case("receiver holds two", one, body, List.of(s3, s1), t), true),
            new Edge(
                new Case(
                    "unknown scheme ignored",
                    "t=" + t + ",v0=00ff,v1=" + hex,
                    body,
                    List.of(s1),
                    t),
                true),
            new Edge(new Case("spaces", "t=" + t + ", v1=" + hex, body, List.of(s1), t), true),
            new Edge(new Case("no t", "v1=" + hex, body, List.of(s1), t), false),
            new Edge(new Case("no v1", "t=" + t, body, List.of(s1), t), false),
            new Edge(
                new Case("two t", "t=" + t + ",t=" + t + ",v1=" + hex, body, List.of(s1), t),
                false),
            new Edge(
                new Case("t not digits", "t=" + t + "a,v1=" + hex, body, List.of(s1), t), false),
            new Edge(new Case("t signed", "t=+" + t + ",v1=" + hex, body, List.of(s1), t), false),
            new Edge(new Case("t empty", "t=,v1=" + hex, body, List.of(s1), t), false),
            new Edge(
                new Case("t moved", "t=" + (t + 1) + ",v1=" + hex, body, List.of(s1), t), false),
            new Edge(new Case("stray part", one + ",garbage", body, List.of(s1), t), false),
            new Edge(new Case("empty part", one + ",", body, List.of(s1), t), false),
            new Edge(new Case("empty header", "", body, List.of(s1), t), false),
            new Edge(new Case("non-ASCII v1", "t=" + t + ",v1=é", body, List.of(s1), t), false),
            new Edge(
                new Case(
                    "empty body",
                    WebhookSignature.header(t, new byte[0], List.of(s1)),
                    new byte[0],
                    List.of(s1),
                    t),
                true),
            new Edge(
                new Case(
                    "bytes that aren't UTF-8",
                    WebhookSignature.header(t, binary, List.of(s1)),
                    binary,
                    List.of(s1),
                    t),
                true));
    for (Edge e : edges) {
      assertThat(e.c().expected())
          .as("payflow's own answer to %s", e.c().label())
          .isEqualTo(e.answer());
      fixed.add(e.c());
    }

    // Generated: random bodies, one or two signing secrets, a receiver holding one or two of a
    // pool, clocks either side of the tolerance, and now and then a body changed in transit.
    Random random = new Random(new SecureRandom().nextLong());
    List<String> pool = freshSecrets(random, 3);
    for (int i = 0; i < 300; i++) {
      byte[] b = new byte[random.nextInt(2_000)];
      random.nextBytes(b);
      long at = 1_700_000_000L + random.nextInt(200_000_000);
      List<String> signing = new ArrayList<>(List.of(pool.get(random.nextInt(3))));
      if (random.nextBoolean()) {
        signing.add(pool.get(random.nextInt(3)));
      }
      List<String> held = new ArrayList<>(List.of(pool.get(random.nextInt(3))));
      if (random.nextInt(4) == 0) {
        held.add(pool.get(random.nextInt(3)));
      }
      String header = WebhookSignature.header(at, b, signing);
      byte[] received = b;
      if (b.length > 0 && random.nextInt(5) == 0) {
        received = b.clone();
        received[random.nextInt(b.length)] ^= (byte) (1 << random.nextInt(8));
      }
      fixed.add(new Case("generated " + i, header, received, held, at + random.nextInt(801) - 400));
    }
    return fixed;
  }

  private static void assertSameAnswers(String example, List<Case> cases, List<Boolean> answers) {
    assertThat(answers).as("%s answered every case", example).hasSize(cases.size());
    int accepted = 0;
    for (int i = 0; i < cases.size(); i++) {
      Case c = cases.get(i);
      assertThat(answers.get(i)).as("%s on %s", example, c.label()).isEqualTo(c.expected());
      accepted += answers.get(i) ? 1 : 0;
    }
    assertThat(accepted).as("both answers occur").isBetween(1, cases.size() - 1);
  }

  private static String run(Path cwd, String input, Map<String, String> env, String... command)
      throws Exception {
    ProcessBuilder pb = new ProcessBuilder(command).directory(cwd.toFile());
    pb.environment().putAll(env);
    pb.redirectError(ProcessBuilder.Redirect.INHERIT);
    Process p = pb.start();
    CompletableFuture<String> out =
        CompletableFuture.supplyAsync(
            () -> {
              try {
                return new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
              } catch (IOException e) {
                throw new IllegalStateException(e);
              }
            });
    try (var stdin = p.getOutputStream()) {
      stdin.write(input.getBytes(StandardCharsets.UTF_8));
    }
    assertThat(p.waitFor(PROCESS_LIMIT.toSeconds(), TimeUnit.SECONDS))
        .as(String.join(" ", command))
        .isTrue();
    assertThat(p.exitValue()).as(String.join(" ", command)).isZero();
    return out.get(10, TimeUnit.SECONDS);
  }

  private static List<Boolean> zerosAndOnes(String out) {
    return out.lines().map(l -> l.strip().equals("1")).toList();
  }

  private static String lines(List<Case> cases) {
    return cases.stream().map(Case::line).collect(Collectors.joining("\n", "", "\n"));
  }

  // ---------------------------------------------------------------------------------------------

  @Test
  void theTestVectorIsWhatPayflowSends() {
    Map<String, String> v = vector();
    long t = Long.parseLong(v.get("timestamp"));
    assertThat(WebhookSignature.header(t, body(), List.of(v.get("secret"))))
        .isEqualTo(v.get("signature"));
    assertThat(WebhookSignature.header(t, body(), List.of(v.get("secret"), v.get("previous"))))
        .as("the new secret's v1 first, as a rotation sends it")
        .isEqualTo(v.get("rotating"));
    for (String key : List.of("secret", "previous")) {
      for (long now : List.of(t - 300, t + 300)) {
        assertThat(WebhookSignature.verify(v.get("rotating"), body(), v.get(key), now, TOLERANCE))
            .isTrue();
      }
      assertThat(WebhookSignature.verify(v.get("rotating"), body(), v.get(key), t + 301, TOLERANCE))
          .isFalse();
    }
    assertThat(WebhookSignature.verify(v.get("signature"), body(), v.get("previous"), t, TOLERANCE))
        .isFalse();
  }

  @Test
  void theExampleBodyIsPostgressOwnRenderingAsDeliveriesCarryIt() {
    // Deliveries send payload::text of a jsonb column, so the example must be a fixed point.
    String rendered =
        jdbc.sql("SELECT CAST(:b AS jsonb)::text")
            .param("b", examples.get("body"))
            .query(String.class)
            .single();
    assertThat(rendered).isEqualTo(examples.get("body"));
    assertThat(examples.get("body"))
        .contains("\"event_type\": \"order.completed\"")
        .doesNotContain("\n");
  }

  @Test
  void theGuideListsEveryEventTypeAndNoOther() throws IOException {
    String md = Files.readString(GUIDE, StandardCharsets.UTF_8);
    int start = md.indexOf("The types are ");
    int end = md.indexOf(". Two kinds of", start);
    List<String> listed = new ArrayList<>();
    Matcher m = Pattern.compile("`([a-z_.]+)`").matcher(md.substring(start, end));
    while (m.find()) {
      listed.add(m.group(1));
    }
    assertThat(listed).containsExactlyElementsOf(WebhookEvents.TYPES);
  }

  @Test
  void thePythonExampleAnswersAsPayflowDoes() throws Exception {
    Path d = Files.createDirectories(dir.resolve("python"));
    Files.writeString(d.resolve("payflow_webhooks.py"), examples.get("python"));
    Files.writeString(
        d.resolve("driver.py"),
        """
        import base64
        import sys

        sys.path.insert(0, sys.argv[1])
        from payflow_webhooks import verify

        for line in sys.stdin:
            h, b, n, s = line.rstrip("\\n").split("\\t")
            secrets = [base64.b64decode(x).decode() for x in s.split(",")]
            header = base64.b64decode(h).decode()
            print(1 if verify(header, base64.b64decode(b), secrets, now=int(n)) else 0)
        """);
    List<Case> cases = cases();
    String out = run(d, lines(cases), Map.of(), "python3", "-I", "driver.py", d.toString());
    assertSameAnswers("python", cases, zerosAndOnes(out));
  }

  @Test
  void theNodeExampleAnswersAsPayflowDoes() throws Exception {
    Path d = Files.createDirectories(dir.resolve("node"));
    Files.writeString(d.resolve("payflow-webhooks.js"), examples.get("node"));
    Files.writeString(
        d.resolve("driver.js"),
        """
        const { verify } = require(process.argv[2]);
        const d = (x) => Buffer.from(x, "base64");
        const lines = require("node:fs").readFileSync(0, "utf8").split("\\n").filter((l) => l);
        const out = lines.map((l) => {
          const [h, b, n, s] = l.split("\\t");
          const secrets = s.split(",").map((x) => d(x).toString("utf8"));
          return verify(d(h).toString("utf8"), d(b), secrets, undefined, Number(n)) ? "1" : "0";
        });
        process.stdout.write(out.join("\\n") + "\\n");
        """);
    List<Case> cases = cases();
    String out =
        run(
            d,
            lines(cases),
            Map.of(),
            "node",
            "driver.js",
            d.resolve("payflow-webhooks.js").toString());
    assertSameAnswers("node", cases, zerosAndOnes(out));
  }

  @Test
  void theJavaExampleAnswersAsPayflowDoes() throws Exception {
    Path d = Files.createDirectories(dir.resolve("java"));
    Path source = d.resolve("PayflowWebhooks.java");
    Files.writeString(source, examples.get("java"));
    JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
    assertThat(
            javac.run(
                null, null, null, "-Xlint:all", "-Werror", "-d", d.toString(), source.toString()))
        .as("the Java example compiles without warnings")
        .isZero();
    try (URLClassLoader loader = new URLClassLoader(new URL[] {d.toUri().toURL()}, null)) {
      Method verify =
          loader
              .loadClass("PayflowWebhooks")
              .getMethod("verify", String.class, byte[].class, List.class, long.class);
      List<Case> cases = cases();
      List<Boolean> answers = new ArrayList<>();
      for (Case c : cases) {
        answers.add((Boolean) verify.invoke(null, c.header(), c.body(), c.secrets(), c.now()));
      }
      assertSameAnswers("java", cases, answers);
    }
  }

  @Test
  void theOpensslCommandRecomputesTheSignature() throws Exception {
    Path d = Files.createDirectories(dir.resolve("openssl"));
    Map<String, String> v = vector();
    Random random = new Random(new SecureRandom().nextLong());
    List<String> secrets = new ArrayList<>(freshSecrets(random, 4));
    secrets.add(v.get("secret"));
    List<byte[]> bodies = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      byte[] b = new byte[random.nextInt(3_000)];
      random.nextBytes(b);
      bodies.add(b);
    }
    bodies.add(body());
    for (int i = 0; i < secrets.size(); i++) {
      long t = 1_700_000_000L + random.nextInt(200_000_000);
      Files.write(d.resolve("body.json"), bodies.get(i));
      String header = WebhookSignature.header(t, bodies.get(i), List.of(secrets.get(i)));
      String out =
          run(
              d,
              "",
              Map.of("t", Long.toString(t), "secret", secrets.get(i)),
              "bash",
              "-c",
              examples.get("openssl"));
      assertThat("t=" + t + ",v1=" + out.strip()).isEqualTo(header);
    }
  }

  @Test
  void theReceiverAcceptsSignedDeliveriesOnceAndRefusesTheRest() throws Exception {
    Path d = Files.createDirectories(dir.resolve("receiver"));
    Files.writeString(d.resolve("payflow_webhooks.py"), examples.get("python"));
    Files.writeString(d.resolve("receiver.py"), examples.get("receiver"));
    Map<String, String> v = vector();
    String held = v.get("previous");
    ProcessBuilder pb =
        new ProcessBuilder("python3", "-s", "-E", "receiver.py")
            .directory(d.toFile())
            .redirectError(ProcessBuilder.Redirect.INHERIT);
    pb.environment().put("PAYFLOW_WEBHOOK_SECRETS", held);
    pb.environment().put("PORT", "0");
    Process p = pb.start();
    try {
      BufferedReader out =
          new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
      List<String> printed = new CopyOnWriteArrayList<>();
      CompletableFuture<String> listening = CompletableFuture.supplyAsync(() -> readLine(out));
      String first = listening.get(PROCESS_LIMIT.toSeconds(), TimeUnit.SECONDS);
      assertThat(first).startsWith("listening on ");
      int port = Integer.parseInt(first.substring("listening on ".length()).strip());
      Thread.ofVirtual()
          .start(
              () -> {
                String line;
                while ((line = readLine(out)) != null) {
                  printed.add(line);
                }
              });

      SafeHttpSender sender =
          new SafeHttpSender(
              AddressPolicy.allowing(List.of("127.0.0.1/32")),
              SafeHttpSender.Resolver.SYSTEM,
              SSLContext.getDefault(),
              new SafeHttpSender.Options(Duration.ofSeconds(3), Duration.ofSeconds(10), 1024),
              "payflow-webhooks/test");
      String url = "http://127.0.0.1:" + port + "/hooks/payflow";
      byte[] body = body();
      String eventId = "0b6f3f8e-2a4c-4d71-9a51-6f0e5b7c9d12";
      long now = System.currentTimeMillis() / 1000;

      // A rotation in progress: payflow signs with the new and the old secret, and this receiver
      // has only the old one deployed so far.
      List<String> signing = List.of(v.get("secret"), held);
      Answered a = deliver(sender, url, body, now, signing, eventId, 1);
      assertThat(a.status()).isEqualTo(200);
      assertThat(a.body()).isEqualTo("ok");
      // The same event again, as after a lost answer or a replay: acknowledged, not handled twice.
      Answered again = deliver(sender, url, body, now + 30, signing, eventId, 2);
      assertThat(again.status()).isEqualTo(200);
      assertThat(again.body()).isEqualTo("duplicate");

      // Changed on the way, signed with a secret it doesn't hold, or captured and sent later.
      byte[] changed = (examples.get("body") + "\n").getBytes(StandardCharsets.UTF_8);
      String other = UUID.randomUUID().toString();
      for (Answered refused :
          List.of(
              deliver(sender, url, changed, body, now, signing, other, 1),
              deliver(sender, url, body, now, List.of(v.get("secret")), other, 1),
              deliver(sender, url, body, now - 301, signing, other, 1))) {
        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.body()).isEqualTo("bad signature");
      }

      // A ping, which has no seq.
      String ping =
          "{\"event_id\": \"" + other + "\", \"event_type\": \"ping\", \"merchant_id\": \"acme\"}";
      Answered pinged =
          deliver(sender, url, ping.getBytes(StandardCharsets.UTF_8), now, List.of(held), other, 1);
      assertThat(pinged.status()).isEqualTo(200);

      long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (printed.size() < 2 && System.nanoTime() < until) {
        Thread.sleep(20);
      }
      assertThat(printed)
          .containsExactly("order.completed " + eventId + " 5", "ping " + other + " None");
    } finally {
      p.destroy();
      if (!p.waitFor(10, TimeUnit.SECONDS)) {
        p.destroyForcibly();
      }
    }
  }

  private static Answered deliver(
      SafeHttpSender sender,
      String url,
      byte[] body,
      long at,
      List<String> secrets,
      String eventId,
      int attempt) {
    return deliver(sender, url, body, body, at, secrets, eventId, attempt);
  }

  /** Sends {@code body} with a signature over {@code signed}, as payflow's dispatcher does. */
  private static Answered deliver(
      SafeHttpSender sender,
      String url,
      byte[] body,
      byte[] signed,
      long at,
      List<String> secrets,
      String eventId,
      int attempt) {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put(WebhookSignature.HEADER, WebhookSignature.header(at, signed, secrets));
    headers.put(WebhookDispatcher.HEADER_EVENT_ID, eventId);
    headers.put(WebhookDispatcher.HEADER_EVENT_TYPE, "order.completed");
    headers.put(WebhookDispatcher.HEADER_DELIVERY_ID, UUID.randomUUID().toString());
    headers.put(WebhookDispatcher.HEADER_ATTEMPT, Integer.toString(attempt));
    SafeHttpSender.Outcome o = sender.post(url, headers, body);
    assertThat(o).isInstanceOf(Answered.class);
    return (Answered) o;
  }

  private static String readLine(BufferedReader r) {
    try {
      return r.readLine();
    } catch (IOException e) {
      return null;
    }
  }
}
