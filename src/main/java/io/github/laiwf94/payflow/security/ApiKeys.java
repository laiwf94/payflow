package io.github.laiwf94.payflow.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Merchant API keys, stored only as SHA-256 hashes.
 *
 * <p>SHA-256 rather than a password hash such as bcrypt: keys are 32 random bytes, so there is
 * nothing to brute-force, and a slow hash on every request would cost CPU for no gain. A presented
 * key is hashed once and compared with every configured hash in constant time, without stopping at
 * a match, so timing reveals neither which merchant matched nor how much of a hash did.
 */
public final class ApiKeys {

  private static final Pattern MERCHANT = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
  private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
  private static final int MAX_KEY_LENGTH = 256;

  private static final class Entry {
    final String merchant;
    final byte[] hash;

    Entry(String merchant, byte[] hash) {
      this.merchant = merchant;
      this.hash = hash;
    }
  }

  private final List<Entry> entries;

  private ApiKeys(List<Entry> entries) {
    this.entries = List.copyOf(entries);
  }

  /**
   * Parses {@code merchant:sha256hex} pairs separated by commas, for example {@code
   * acme:9f86d0...,globex:60303a...}.
   */
  public static ApiKeys parse(String spec) {
    if (spec == null || spec.isBlank()) {
      throw new IllegalArgumentException(
          "no API keys configured: set PAYFLOW_API_KEYS (run 'payflow keygen <merchant>')");
    }
    List<Entry> out = new ArrayList<>();
    for (String part : Pattern.compile(",").splitAsStream(spec).toList()) {
      String p = part.trim();
      int colon = p.indexOf(':');
      if (colon < 0) {
        throw new IllegalArgumentException("API key entries look like merchant:sha256hex");
      }
      String merchant = p.substring(0, colon);
      String hash = p.substring(colon + 1).toLowerCase(java.util.Locale.ROOT);
      if (!MERCHANT.matcher(merchant).matches()) {
        throw new IllegalArgumentException("merchant ids are 1-64 chars of a-z, 0-9, _ and -");
      }
      if (!HASH.matcher(hash).matches()) {
        throw new IllegalArgumentException("key hash for " + merchant + " is not 64 hex digits");
      }
      out.add(new Entry(merchant, HexFormat.of().parseHex(hash)));
    }
    return new ApiKeys(out);
  }

  /** The merchant the key belongs to, if any. */
  public Optional<String> authenticate(String presented) {
    if (presented == null || presented.isEmpty() || presented.length() > MAX_KEY_LENGTH) {
      return Optional.empty();
    }
    byte[] h = sha256(presented);
    String match = null;
    for (Entry e : entries) {
      if (MessageDigest.isEqual(h, e.hash) && match == null) {
        match = e.merchant;
      }
    }
    return Optional.ofNullable(match);
  }

  public static String hash(String key) {
    return HexFormat.of().formatHex(sha256(key));
  }

  /** A new random key: {@code pf_} and 32 random bytes, base64url. */
  public static String generate() {
    byte[] b = new byte[32];
    new SecureRandom().nextBytes(b);
    return "pf_" + Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }

  private static byte[] sha256(String s) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is always available", e);
    }
  }

  @Override
  public String toString() {
    return "ApiKeys[" + entries.size() + " merchants]";
  }
}
