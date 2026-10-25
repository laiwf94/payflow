package io.github.laiwf94.payflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

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
  void mainStartsWithoutWebServer() {
    assertThatCode(
            () -> PayflowApplication.main(new String[] {"--spring.main.web-application-type=none"}))
        .doesNotThrowAnyException();
  }
}
