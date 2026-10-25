package io.github.laiwf94.payflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.laiwf94.payflow.security.ApiKeys;
import io.github.laiwf94.payflow.support.Containers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PayflowApplicationTest {

  @Autowired ApplicationContext context;

  @Test
  void contextStarts() {
    assertThat(context.getBean(PayflowApplication.class)).isNotNull();
  }

  @Test
  void startsStandaloneFromTheCommandLineArguments() {
    try (var app =
        PayflowApplication.start(
            "--spring.main.web-application-type=none",
            "--spring.datasource.url=" + Containers.POSTGRES.getJdbcUrl(),
            "--spring.datasource.username=payflow",
            "--spring.datasource.password=payflow")) {
      assertThat(app.isRunning()).isTrue();
    }
  }

  @Test
  void keygenPrintsAKeyAndTheMatchingConfigEntry() {
    String out = PayflowApplication.keygen("acme");
    String key = out.lines().findFirst().orElseThrow().substring("key:  ".length());
    assertThat(out).contains("PAYFLOW_API_KEYS entry: acme:" + ApiKeys.hash(key));
    assertThatThrownBy(() -> PayflowApplication.keygen("Not Valid"))
        .isInstanceOf(IllegalArgumentException.class);
    PayflowApplication.main(new String[] {"keygen", "acme"});
  }
}
