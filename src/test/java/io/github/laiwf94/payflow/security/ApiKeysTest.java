package io.github.laiwf94.payflow.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ApiKeysTest {

  @Test
  void authenticatesByHash() {
    String a = ApiKeys.generate();
    String b = ApiKeys.generate();
    ApiKeys keys =
        ApiKeys.parse(
            "acme:"
                + ApiKeys.hash(a)
                + ", globex:"
                + ApiKeys.hash(b).toUpperCase(java.util.Locale.ROOT));
    assertThat(keys.authenticate(a)).contains("acme");
    assertThat(keys.authenticate(b)).contains("globex");
    assertThat(keys.authenticate(a + "x")).isEmpty();
    assertThat(keys.authenticate("")).isEmpty();
    assertThat(keys.authenticate(null)).isEmpty();
    assertThat(keys.authenticate("x".repeat(257))).isEmpty();
    assertThat(keys).hasToString("ApiKeys[2 merchants]");
  }

  @Test
  void generatedKeysAreLongAndDistinct() {
    String k = ApiKeys.generate();
    assertThat(k).startsWith("pf_").hasSize(46);
    assertThat(ApiKeys.generate()).isNotEqualTo(k);
    assertThat(ApiKeys.hash("abc"))
        .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        " ",
        "acme",
        "Acme:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        "acme:abc",
        "acme:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015zz",
        ":ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
      })
  void rejectsBadConfiguration(String spec) {
    assertThatThrownBy(() -> ApiKeys.parse(spec)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsNullConfiguration() {
    assertThatThrownBy(() -> ApiKeys.parse(null)).hasMessageContaining("PAYFLOW_API_KEYS");
  }
}
