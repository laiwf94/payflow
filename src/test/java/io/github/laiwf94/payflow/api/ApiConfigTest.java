package io.github.laiwf94.payflow.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.json.JsonMapper;

class ApiConfigTest {

  record Holder(String value) {}

  private static ObjectMapper strict() {
    JsonMapper.Builder b = JsonMapper.builder();
    new ApiConfig().strictText().customize(b);
    return b.build();
  }

  @Test
  void theStrictMapperRefusesScalarsWhereStringsBelong() {
    ObjectMapper strict = strict();
    for (String scalar : new String[] {"7", "1.5", "true"}) {
      assertThatThrownBy(() -> strict.readValue("{\"value\":" + scalar + "}", Holder.class))
          .as(scalar)
          .isInstanceOf(MismatchedInputException.class);
    }
    assertThat(strict.readValue("{\"value\":\"7\"}", Holder.class).value()).isEqualTo("7");
  }

  @Test
  void theLenientMapperForProvidersStillAcceptsThem() {
    ObjectMapper lenient = ApiConfig.lenientText(strict());
    assertThat(lenient.readValue("{\"value\":7}", Holder.class).value()).isEqualTo("7");
    assertThat(lenient.readValue("{\"value\":1.5}", Holder.class).value()).isEqualTo("1.5");
    assertThat(lenient.readValue("{\"value\":true}", Holder.class).value()).isEqualTo("true");
  }
}
