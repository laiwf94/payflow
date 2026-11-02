package io.github.laiwf94.payflow.api;

import io.github.laiwf94.payflow.order.OrderFilter;
import io.github.laiwf94.payflow.order.OrderPosition;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Opaque, signed cursors for the order listing.
 *
 * <p>A cursor is 55 base64url characters: a version byte, the last order's {@code created_at} in
 * microseconds and its id, followed by the first 128 bits of an HMAC-SHA256 over the merchant, the
 * listing's filters and those 25 bytes. The signature means a client can't fabricate a position,
 * can't continue one merchant's listing as another, and can't change the filters halfway through
 * and silently get a page of a different listing; any of those is a 400 {@code invalid_cursor}. It
 * also leaves the format free to change, since clients have nothing to parse.
 *
 * <p>Every instance behind one load balancer needs the same secret, or a cursor from one instance
 * is refused by the next. {@code payflow.cursor-secrets} takes a comma-separated list: the first
 * signs, and all of them verify, so a secret rotates without breaking listings in progress by
 * deploying {@code new,old} and later {@code new}.
 */
final class OrderCursors {

  private static final Logger log = LoggerFactory.getLogger(OrderCursors.class);

  static final int MIN_SECRET_LENGTH = 32;
  private static final byte VERSION = 1;
  private static final int BODY_BYTES = 1 + Long.BYTES + 2 * Long.BYTES;
  private static final int TAG_BYTES = 16;
  private static final int CURSOR_CHARS = 55;
  private static final String ALGORITHM = "HmacSHA256";
  private static final byte[] CONTEXT = "payflow orders cursor\n".getBytes(StandardCharsets.UTF_8);
  private static final Pattern COMMA = Pattern.compile(",");

  /**
   * The cursor is malformed, forged, from another merchant or from a listing with other filters.
   */
  static final class InvalidCursor extends RuntimeException {
    private static final long serialVersionUID = 1L;

    InvalidCursor() {
      super("cursor is not valid for this listing; start again without one");
    }
  }

  private final List<SecretKeySpec> keys;

  private OrderCursors(List<SecretKeySpec> keys) {
    this.keys = List.copyOf(keys);
  }

  /**
   * Parses a comma-separated list of secrets, each at least {@value #MIN_SECRET_LENGTH} characters.
   * Blank means a random secret for this process only, which is fine for one instance but makes
   * cursors fail across a restart or between instances.
   */
  static OrderCursors of(String spec) {
    List<SecretKeySpec> keys = new ArrayList<>();
    if (spec == null || spec.isBlank()) {
      byte[] random = new byte[32];
      new SecureRandom().nextBytes(random);
      keys.add(new SecretKeySpec(random, ALGORITHM));
      log.warn(
          "PAYFLOW_CURSOR_SECRETS is not set: order listing cursors are signed with a random"
              + " secret and stop working after a restart or on another instance");
    } else {
      for (String part : COMMA.split(spec, -1)) {
        String secret = part.strip();
        if (secret.length() < MIN_SECRET_LENGTH) {
          throw new IllegalArgumentException(
              "each cursor secret must be at least " + MIN_SECRET_LENGTH + " characters");
        }
        keys.add(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
      }
    }
    return new OrderCursors(keys);
  }

  /** A cursor that continues {@code merchant}'s listing under {@code filter} after {@code at}. */
  String encode(String merchant, OrderFilter filter, OrderPosition at) {
    Instant t = at.createdAt();
    if (!t.equals(t.truncatedTo(ChronoUnit.MICROS))) {
      throw new IllegalArgumentException("positions come from the database, in microseconds");
    }
    byte[] body =
        ByteBuffer.allocate(BODY_BYTES)
            .put(VERSION)
            .putLong(ChronoUnit.MICROS.between(Instant.EPOCH, t))
            .putLong(at.id().getMostSignificantBits())
            .putLong(at.id().getLeastSignificantBits())
            .array();
    byte[] out = Arrays.copyOf(body, BODY_BYTES + TAG_BYTES);
    System.arraycopy(tag(keys.getFirst(), merchant, filter, body), 0, out, BODY_BYTES, TAG_BYTES);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(out);
  }

  /**
   * The position {@code cursor} names.
   *
   * @throws InvalidCursor unless one of the secrets signed it for this merchant and filter
   */
  OrderPosition decode(String merchant, OrderFilter filter, String cursor) {
    if (cursor.length() != CURSOR_CHARS) {
      throw new InvalidCursor();
    }
    byte[] raw;
    try {
      raw = Base64.getUrlDecoder().decode(cursor);
    } catch (IllegalArgumentException e) {
      throw new InvalidCursor();
    }
    byte[] body = Arrays.copyOf(raw, BODY_BYTES);
    byte[] presented = Arrays.copyOfRange(raw, BODY_BYTES, raw.length);
    boolean valid = false;
    // Every key is tried, without stopping at a match, so timing doesn't say which one signed it.
    for (SecretKeySpec key : keys) {
      valid |= MessageDigest.isEqual(tag(key, merchant, filter, body), presented);
    }
    if (!valid || body[0] != VERSION) {
      throw new InvalidCursor();
    }
    ByteBuffer b = ByteBuffer.wrap(body, 1, BODY_BYTES - 1);
    Instant createdAt = Instant.EPOCH.plus(b.getLong(), ChronoUnit.MICROS);
    return new OrderPosition(createdAt, new UUID(b.getLong(), b.getLong()));
  }

  private static byte[] tag(SecretKeySpec key, String merchant, OrderFilter filter, byte[] body) {
    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(key);
      mac.update(CONTEXT);
      mac.update((merchant + "\n" + filter.canonical() + "\n").getBytes(StandardCharsets.UTF_8));
      return Arrays.copyOf(mac.doFinal(body), TAG_BYTES);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA256 is part of every JDK", e);
    }
  }

  @Override
  public String toString() {
    return "OrderCursors[" + keys.size() + " secrets, redacted]";
  }
}
