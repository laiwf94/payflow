package io.github.laiwf94.payflow;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.laiwf94.payflow.support.Containers;
import io.github.laiwf94.payflow.support.TestInfrastructure;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestInfrastructure.class)
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
}
