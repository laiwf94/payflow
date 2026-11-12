package io.github.laiwf94.payflow.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Seals secrets that payflow must store and later read back, with AES-256-GCM: webhook signing
 * secrets, which sign every delivery, and stored idempotent answers that carry one.
 *
 * <p>Such a secret can't be hashed like an API key, since it is needed again in the clear. Each
 * value gets a fresh 96-bit nonce, and the caller's context (what the value is and which row it
 * belongs to) is bound in as associated data, so a ciphertext copied into another row, or used for
 * another purpose, fails to open rather than being taken for that row's secret.
 *
 * <p>{@code payflow.secret-keys} is a comma-separated list of {@code id:base64key}, each key 32
 * bytes. The first seals; every one opens, by the id stored next to the ciphertext, so a key
 * rotates by deploying {@code new,old} and dropping {@code old} once nothing sealed with it is
 * left. Blank means a random key for this process only: fine for a demo, but values sealed with it
 * can't be opened after a restart or by another instance.
 */
public final class SecretBox {

  private static final Logger log = LoggerFactory.getLogger(SecretBox.class);

  static final int KEY_BYTES = 32;
  static final int NONCE_BYTES = 12;
  private static final int TAG_BITS = 128;
  private static final String TRANSFORMATION = "AES/GCM/NoPadding";
  private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,31}");
  private static final Pattern COMMA = Pattern.compile(",");
  private static final byte[] CONTEXT = "payflow sealed v1\n".getBytes(StandardCharsets.UTF_8);

  /** A sealed value: the id of the key that sealed it, the nonce, and ciphertext with tag. */
  public static final class Sealed {
    private final String keyId;
    private final byte[] nonce;
    private final byte[] ciphertext;

    public Sealed(String keyId, byte[] nonce, byte[] ciphertext) {
      this.keyId = keyId;
      this.nonce = nonce.clone();
      this.ciphertext = ciphertext.clone();
    }

    public String keyId() {
      return keyId;
    }

    public byte[] nonce() {
      return nonce.clone();
    }

    public byte[] ciphertext() {
      return ciphertext.clone();
    }
  }

  /** The value can't be opened: its key is no longer configured, or it was tampered with. */
  public static final class CannotOpen extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public CannotOpen(String message) {
      super(message);
    }
  }

  private final SecureRandom random = new SecureRandom();
  private final String sealingId;
  private final Map<String, SecretKeySpec> keys;

  private SecretBox(Map<String, SecretKeySpec> keys) {
    this.sealingId = keys.keySet().iterator().next();
    this.keys = Map.copyOf(keys);
  }

  /**
   * Parses {@code id:base64key} entries separated by commas; see the class comment.
   *
   * @throws IllegalArgumentException on a malformed entry, a key that isn't 32 bytes, or a repeated
   *     id
   */
  public static SecretBox of(String spec) {
    Map<String, SecretKeySpec> keys = new LinkedHashMap<>();
    if (spec == null || spec.isBlank()) {
      byte[] key = new byte[KEY_BYTES];
      byte[] suffix = new byte[6];
      SecureRandom r = new SecureRandom();
      r.nextBytes(key);
      r.nextBytes(suffix);
      keys.put("ephemeral-" + HexFormat.of().formatHex(suffix), new SecretKeySpec(key, "AES"));
      log.warn(
          "PAYFLOW_SECRET_KEYS is not set: stored secrets are sealed with a random key"
              + " and can't be read after a restart or on another instance");
      return new SecretBox(keys);
    }
    for (String part : COMMA.split(spec, -1)) {
      String p = part.strip();
      int colon = p.indexOf(':');
      if (colon < 0) {
        throw new IllegalArgumentException("secret keys look like id:base64key");
      }
      String id = p.substring(0, colon);
      if (!ID.matcher(id).matches()) {
        throw new IllegalArgumentException(
            "secret key ids are 1-32 chars of a-z, 0-9, _ and -, starting with a letter"
                + " or digit");
      }
      byte[] key;
      try {
        key = Base64.getDecoder().decode(p.substring(colon + 1));
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("secret key " + id + " is not base64", e);
      }
      if (key.length != KEY_BYTES) {
        throw new IllegalArgumentException(
            "secret key " + id + " must be " + KEY_BYTES + " bytes, not " + key.length);
      }
      if (keys.put(id, new SecretKeySpec(key, "AES")) != null) {
        throw new IllegalArgumentException("secret key id " + id + " is repeated");
      }
    }
    return new SecretBox(keys);
  }

  /** A new random key in the form {@link #of} takes: 32 bytes, base64. */
  public static String generateKey() {
    byte[] b = new byte[KEY_BYTES];
    new SecureRandom().nextBytes(b);
    return Base64.getEncoder().encodeToString(b);
  }

  /** The id of the key that seals new values. */
  public String sealingKeyId() {
    return sealingId;
  }

  /** Seals {@code plaintext} for the purpose and row that {@code context} names. */
  public Sealed seal(String plaintext, String context) {
    byte[] nonce = new byte[NONCE_BYTES];
    random.nextBytes(nonce);
    try {
      Cipher c = cipher(Cipher.ENCRYPT_MODE, keys.get(sealingId), nonce, context);
      return new Sealed(sealingId, nonce, c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("AES-GCM is part of every JDK", e);
    }
  }

  /**
   * Opens a value sealed for {@code context}.
   *
   * @throws CannotOpen when its key isn't configured, or the value or context don't match
   */
  public String open(Sealed sealed, String context) {
    SecretKeySpec key = keys.get(sealed.keyId());
    if (key == null) {
      throw new CannotOpen(
          "secret key " + sealed.keyId() + " is not configured; add it to PAYFLOW_SECRET_KEYS");
    }
    if (sealed.nonce().length != NONCE_BYTES) {
      throw new CannotOpen("sealed value has a malformed nonce");
    }
    try {
      Cipher c = cipher(Cipher.DECRYPT_MODE, key, sealed.nonce(), context);
      return new String(c.doFinal(sealed.ciphertext()), StandardCharsets.UTF_8);
    } catch (AEADBadTagException e) {
      throw new CannotOpen("sealed value doesn't open: tampered with, or sealed for another row");
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("AES-GCM is part of every JDK", e);
    }
  }

  private static Cipher cipher(int mode, SecretKeySpec key, byte[] nonce, String context)
      throws GeneralSecurityException {
    Cipher c = Cipher.getInstance(TRANSFORMATION);
    c.init(mode, key, new GCMParameterSpec(TAG_BITS, nonce));
    c.updateAAD(CONTEXT);
    c.updateAAD(context.getBytes(StandardCharsets.UTF_8));
    return c;
  }

  @Override
  public String toString() {
    List<String> ids = new ArrayList<>(keys.keySet());
    ids.sort(null);
    return "SecretBox[sealing with " + sealingId + ", opening " + ids + ", keys redacted]";
  }
}
