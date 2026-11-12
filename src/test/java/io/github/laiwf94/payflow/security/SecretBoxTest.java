package io.github.laiwf94.payflow.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SecretBoxTest {

  private static final String OLD = "old:" + SecretBox.generateKey();
  private static final String NEW = "new:" + SecretBox.generateKey();

  @Test
  void aSealedValueOpensForItsOwnContextOnly() {
    SecretBox box = SecretBox.of(NEW);
    SecretBox.Sealed s = box.seal("whsec_abc", "endpoint\nacme\n1");
    assertThat(s.keyId()).isEqualTo("new");
    assertThat(s.nonce()).hasSize(SecretBox.NONCE_BYTES);
    assertThat(new String(s.ciphertext(), java.nio.charset.StandardCharsets.ISO_8859_1))
        .doesNotContain("whsec_abc");
    assertThat(box.open(s, "endpoint\nacme\n1")).isEqualTo("whsec_abc");

    assertThatThrownBy(() -> box.open(s, "endpoint\nacme\n2"))
        .as("copied into another row")
        .isInstanceOf(SecretBox.CannotOpen.class)
        .hasMessageContaining("sealed for another row");
    assertThatThrownBy(() -> box.open(s, "endpoint\nglobex\n1"))
        .isInstanceOf(SecretBox.CannotOpen.class);
  }

  @Test
  void everyBitOfTheCiphertextAndNonceIsCovered() {
    SecretBox box = SecretBox.of(NEW);
    SecretBox.Sealed s = box.seal("whsec_tamper", "c");
    byte[] ct = s.ciphertext();
    for (int bit = 0; bit < ct.length * 8; bit++) {
      byte[] flipped = ct.clone();
      flipped[bit / 8] ^= (byte) (1 << (bit % 8));
      SecretBox.Sealed bad = new SecretBox.Sealed("new", s.nonce(), flipped);
      assertThatThrownBy(() -> box.open(bad, "c")).isInstanceOf(SecretBox.CannotOpen.class);
    }
    byte[] nonce = s.nonce();
    nonce[0] ^= 1;
    assertThatThrownBy(() -> box.open(new SecretBox.Sealed("new", nonce, ct), "c"))
        .isInstanceOf(SecretBox.CannotOpen.class);
    assertThatThrownBy(
            () -> box.open(new SecretBox.Sealed("new", Arrays.copyOf(nonce, 8), ct), "c"))
        .isInstanceOf(SecretBox.CannotOpen.class)
        .hasMessageContaining("nonce");
    assertThatThrownBy(() -> box.open(new SecretBox.Sealed("new", s.nonce(), new byte[3]), "c"))
        .isInstanceOf(SecretBox.CannotOpen.class);
  }

  @Test
  void eachSealHasItsOwnNonce() {
    SecretBox box = SecretBox.of(NEW);
    Set<String> nonces = new HashSet<>();
    Set<String> ciphertexts = new HashSet<>();
    for (int i = 0; i < 200; i++) {
      SecretBox.Sealed s = box.seal("same", "same");
      nonces.add(Base64.getEncoder().encodeToString(s.nonce()));
      ciphertexts.add(Base64.getEncoder().encodeToString(s.ciphertext()));
    }
    assertThat(nonces).hasSize(200);
    assertThat(ciphertexts).hasSize(200);
  }

  @Test
  void keysRotateTheFirstSealsAndAllOpen() {
    SecretBox before = SecretBox.of(OLD);
    SecretBox.Sealed old = before.seal("whsec_old", "c");

    SecretBox during = SecretBox.of(NEW + "," + OLD);
    assertThat(during.sealingKeyId()).isEqualTo("new");
    assertThat(during.open(old, "c")).isEqualTo("whsec_old");
    SecretBox.Sealed fresh = during.seal("whsec_new", "c");
    assertThat(fresh.keyId()).isEqualTo("new");
    assertThatThrownBy(() -> before.open(fresh, "c"))
        .isInstanceOf(SecretBox.CannotOpen.class)
        .hasMessage("secret key new is not configured; add it to PAYFLOW_SECRET_KEYS");

    SecretBox after = SecretBox.of(" " + NEW + " ");
    assertThat(after.open(fresh, "c")).isEqualTo("whsec_new");
    assertThatThrownBy(() -> after.open(old, "c")).isInstanceOf(SecretBox.CannotOpen.class);
  }

  @Test
  void aKeyUnderAnotherIdDoesNotOpen() {
    String key = SecretBox.generateKey();
    SecretBox.Sealed s = SecretBox.of("a:" + key).seal("v", "c");
    SecretBox other =
        SecretBox.of("b:" + SecretBox.generateKey() + ",a:" + SecretBox.generateKey());
    assertThatThrownBy(() -> other.open(s, "c")).isInstanceOf(SecretBox.CannotOpen.class);
  }

  @Test
  void withoutKeysEachProcessHasItsOwn() {
    SecretBox one = SecretBox.of("");
    SecretBox two = SecretBox.of(null);
    assertThat(one.sealingKeyId()).startsWith("ephemeral-").isNotEqualTo(two.sealingKeyId());
    SecretBox.Sealed s = one.seal("v", "c");
    assertThat(one.open(s, "c")).isEqualTo("v");
    assertThatThrownBy(() -> two.open(s, "c")).isInstanceOf(SecretBox.CannotOpen.class);
  }

  @Test
  void malformedKeyListsAreRefused() {
    String key = SecretBox.generateKey();
    assertThatThrownBy(() -> SecretBox.of(key))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("id:base64key");
    assertThatThrownBy(() -> SecretBox.of("Upper:" + key))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ids are");
    assertThatThrownBy(() -> SecretBox.of(":" + key)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SecretBox.of("k:not base64!"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not base64");
    String short16 = Base64.getEncoder().encodeToString(new byte[16]);
    assertThatThrownBy(() -> SecretBox.of("k:" + short16))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("secret key k must be 32 bytes, not 16");
    assertThatThrownBy(() -> SecretBox.of("k:" + key + ",k:" + SecretBox.generateKey()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("repeated");
    assertThatThrownBy(() -> SecretBox.of("k:" + key + ","))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void keysNeverAppearInToString() {
    String key = SecretBox.generateKey();
    SecretBox box = SecretBox.of("b:" + key + ",a:" + SecretBox.generateKey());
    assertThat(box.toString())
        .doesNotContain(key)
        .isEqualTo("SecretBox[sealing with b, opening [a, b], keys redacted]");
  }

  @Test
  void sealedValuesCopyTheirBytes() {
    byte[] nonce = new byte[SecretBox.NONCE_BYTES];
    byte[] ct = new byte[20];
    SecretBox.Sealed s = new SecretBox.Sealed("k", nonce, ct);
    nonce[0] = 1;
    ct[0] = 1;
    s.nonce()[1] = 1;
    assertThat(s.nonce()).containsOnly(0);
    assertThat(s.ciphertext()).containsOnly(0);
  }
}
