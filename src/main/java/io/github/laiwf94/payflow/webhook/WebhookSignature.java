package io.github.laiwf94.payflow.webhook;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The {@value #HEADER} header on every delivery: {@code t=<unix seconds>,v1=<hex>}, where the hex
 * is HMAC-SHA256, keyed with the endpoint's whole secret ({@code whsec_...}, as UTF-8), over {@code
 * t}, a full stop and the raw request body. Signing the timestamp with the body lets a receiver
 * refuse an old request replayed at it; signing the raw bytes means the receiver checks exactly
 * what arrived, before parsing it. A header can carry several {@code v1} values, one per secret
 * that signs, and a receiver accepts the request when any one of them matches.
 */
public final class WebhookSignature {

  public static final String HEADER = "Payflow-Signature";

  /** How old a timestamp {@link #verify} accepts by default, as Stripe's libraries do. */
  public static final Duration DEFAULT_TOLERANCE = Duration.ofMinutes(5);

  private static final HexFormat HEX = HexFormat.of();

  private WebhookSignature() {}

  /** The header for {@code body} sent at {@code timestamp}, signed with each of {@code secrets}. */
  public static String header(long timestamp, byte[] body, List<String> secrets) {
    if (secrets.isEmpty()) {
      throw new IllegalArgumentException("at least one secret signs");
    }
    StringBuilder h = new StringBuilder("t=").append(timestamp);
    for (String s : secrets) {
      h.append(",v1=").append(HEX.formatHex(mac(s, timestamp, body)));
    }
    return h.toString();
  }

  /**
   * What a receiver does: true when {@code header} has a timestamp within {@code tolerance} of
   * {@code nowSeconds}, either side, and a {@code v1} that is the signature of {@code body} at that
   * timestamp under {@code secret}. Signatures are compared in constant time.
   */
  public static boolean verify(
      String header, byte[] body, String secret, long nowSeconds, Duration tolerance) {
    if (header == null) {
      return false;
    }
    Long t = null;
    List<byte[]> candidates = new ArrayList<>();
    for (String part : header.split(",", -1)) {
      int eq = part.indexOf('=');
      if (eq < 0) {
        return false;
      }
      String k = part.substring(0, eq).trim();
      String v = part.substring(eq + 1).trim();
      try {
        if (k.equals("t")) {
          if (t != null || !v.matches("[0-9]{1,12}")) {
            return false;
          }
          t = Long.parseLong(v);
        } else if (k.equals("v1")) {
          candidates.add(HEX.parseHex(v));
        }
      } catch (IllegalArgumentException e) {
        return false;
      }
    }
    if (t == null || candidates.isEmpty() || Math.abs(nowSeconds - t) > tolerance.toSeconds()) {
      return false;
    }
    byte[] expected = mac(secret, t, body);
    boolean ok = false;
    for (byte[] c : candidates) {
      // No early exit: every candidate is compared, so timing doesn't say which one matched.
      ok |= MessageDigest.isEqual(expected, c);
    }
    return ok;
  }

  private static byte[] mac(String secret, long timestamp, byte[] body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      mac.update((timestamp + ".").getBytes(StandardCharsets.US_ASCII));
      return mac.doFinal(body);
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new IllegalStateException("HmacSHA256 is unavailable", e);
    }
  }
}
