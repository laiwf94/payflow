package io.github.laiwf94.payflow.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class WebhookSignatureTest {

  // Test-only keys, built from parts so secret scanners don't take them for real ones.
  private static final String SECRET =
      WebhookEndpointService.SECRET_PREFIX + "test-only-signing-key-one";
  private static final byte[] BODY =
      "{\"event_id\": \"e1\", \"amount\": 4200}".getBytes(StandardCharsets.UTF_8);
  private static final Duration TOLERANCE = WebhookSignature.DEFAULT_TOLERANCE;

  @Test
  void theHeaderIsTheTimestampAndAnHmacOverTimestampDotRawBody() throws Exception {
    String header = WebhookSignature.header(1_700_000_000L, BODY, List.of(SECRET));

    // Computed independently, the way a receiver in any language would.
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    mac.update("1700000000.".getBytes(StandardCharsets.US_ASCII));
    String expected = HexFormat.of().formatHex(mac.doFinal(BODY));
    assertThat(header).isEqualTo("t=1700000000,v1=" + expected);
    assertThat(expected).hasSize(64);
  }

  @Test
  void aReceiverAcceptsTheRealThingAndNothingElse() {
    long t = 1_700_000_000L;
    String header = WebhookSignature.header(t, BODY, List.of(SECRET));

    assertThat(WebhookSignature.verify(header, BODY, SECRET, t + 10, TOLERANCE)).isTrue();
    assertThat(WebhookSignature.verify(header, BODY, SECRET, t - 10, TOLERANCE)).isTrue();
    // A body changed by one byte, another endpoint's secret, a timestamp moved.
    byte[] tampered = BODY.clone();
    tampered[tampered.length - 2] = '1';
    assertThat(WebhookSignature.verify(header, tampered, SECRET, t, TOLERANCE)).isFalse();
    assertThat(WebhookSignature.verify(header, BODY, SECRET + "x", t, TOLERANCE)).isFalse();
    String moved = header.replace("t=1700000000", "t=1700000001");
    assertThat(WebhookSignature.verify(moved, BODY, SECRET, t, TOLERANCE)).isFalse();
    // Replayed later than the tolerance, or sent from the future.
    assertThat(WebhookSignature.verify(header, BODY, SECRET, t + 301, TOLERANCE)).isFalse();
    assertThat(WebhookSignature.verify(header, BODY, SECRET, t - 301, TOLERANCE)).isFalse();
  }

  @Test
  void anyOneOfSeveralSignaturesIsEnough() {
    long t = 1_700_000_000L;
    String other = WebhookEndpointService.SECRET_PREFIX + "test-only-signing-key-two";
    String header = WebhookSignature.header(t, BODY, List.of(other, SECRET));
    assertThat(header.split(",")).hasSize(3);
    assertThat(WebhookSignature.verify(header, BODY, SECRET, t, TOLERANCE)).isTrue();
    assertThat(WebhookSignature.verify(header, BODY, other, t, TOLERANCE)).isTrue();
    // Unknown schemes are ignored, as long as a v1 matches.
    assertThat(WebhookSignature.verify(header + ",v0=abcd", BODY, SECRET, t, TOLERANCE)).isTrue();
  }

  @Test
  void malformedHeadersAreRefused() {
    long t = 1_700_000_000L;
    String good = WebhookSignature.header(t, BODY, List.of(SECRET));
    String v1 = good.substring(good.indexOf("v1="));
    for (String h :
        new String[] {
          "",
          "t=1700000000",
          v1,
          "t=abc," + v1,
          "t=-1700000000," + v1,
          "t=1700000000,t=1700000000," + v1,
          "t=1700000000,v1=zz",
          "t=1700000000,v1",
          "garbage",
        }) {
      assertThat(WebhookSignature.verify(h, BODY, SECRET, t, TOLERANCE)).as(h).isFalse();
    }
    assertThat(WebhookSignature.verify(null, BODY, SECRET, t, TOLERANCE)).isFalse();
    assertThatThrownBy(() -> WebhookSignature.header(t, BODY, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
