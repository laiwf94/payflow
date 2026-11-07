package io.github.laiwf94.payflow.refund;

import static io.github.laiwf94.payflow.refund.RefundState.*;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RefundStateTest {

  @Test
  void aRefundSettlesOnceFromPending() {
    for (RefundState from : values()) {
      for (RefundState to : values()) {
        assertThat(from.canTransitionTo(to))
            .as("%s -> %s", from, to)
            .isEqualTo(from == PENDING && to != PENDING);
      }
    }
    assertThat(PENDING.isTerminal()).isFalse();
    assertThat(SUCCEEDED.isTerminal()).isTrue();
    assertThat(FAILED.isTerminal()).isTrue();
  }
}
