package io.github.laiwf94.payflow.order;

import static io.github.laiwf94.payflow.order.OrderState.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

class OrderStateTest {

  // Written out longhand, independently of the table in OrderState, so a typo in one shows up.
  private static final Set<List<OrderState>> LEGAL =
      Set.of(
          List.of(PENDING, INVENTORY_RESERVED),
          List.of(PENDING, RELEASING_INVENTORY),
          List.of(INVENTORY_RESERVED, PAYMENT_AUTHORIZED),
          List.of(INVENTORY_RESERVED, REVERSING_PAYMENT),
          List.of(INVENTORY_RESERVED, RELEASING_INVENTORY),
          List.of(PAYMENT_AUTHORIZED, PAYMENT_CAPTURED),
          List.of(PAYMENT_AUTHORIZED, REVERSING_PAYMENT),
          List.of(PAYMENT_CAPTURED, COMPLETED),
          List.of(PAYMENT_CAPTURED, CANCELLING_SHIPMENT),
          List.of(CANCELLING_SHIPMENT, REVERSING_PAYMENT),
          List.of(REVERSING_PAYMENT, RELEASING_INVENTORY),
          List.of(RELEASING_INVENTORY, FAILED),
          List.of(RELEASING_INVENTORY, CANCELLED),
          List.of(COMPLETED, REFUNDING),
          List.of(REFUNDING, REFUNDED));

  @Test
  void transitionTableMatchesTheWrittenOutList() {
    for (OrderState from : values()) {
      for (OrderState to : values()) {
        assertThat(from.canTransitionTo(to))
            .as("%s -> %s", from, to)
            .isEqualTo(LEGAL.contains(List.of(from, to)));
      }
    }
  }

  @Test
  void terminalStatesOnlyLeadToRefunds() {
    assertThat(FAILED.next()).isEmpty();
    assertThat(CANCELLED.next()).isEmpty();
    assertThat(REFUNDED.next()).isEmpty();
    assertThat(COMPLETED.next()).containsExactly(REFUNDING);
    assertThat(EnumSet.allOf(OrderState.class).stream().filter(OrderState::isTerminal))
        .containsExactlyInAnyOrder(COMPLETED, FAILED, CANCELLED, REFUNDED);
  }

  @Test
  void compensationTargetsAreLegalTransitions() {
    for (OrderState s :
        List.of(PENDING, INVENTORY_RESERVED, PAYMENT_AUTHORIZED, PAYMENT_CAPTURED)) {
      assertThat(s.canTransitionTo(s.compensation())).as(s.name()).isTrue();
      assertThat(s.compensation().isCompensating()).isTrue();
    }
    assertThatThrownBy(COMPLETED::compensation).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(REVERSING_PAYMENT::compensation).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void onlyStatesBeforeCaptureAreCancellable() {
    assertThat(EnumSet.allOf(OrderState.class).stream().filter(OrderState::isCancellable))
        .containsExactlyInAnyOrder(PENDING, INVENTORY_RESERVED, PAYMENT_AUTHORIZED);
  }

  @Test
  void everyStateIsReachableFromPending() {
    Set<OrderState> seen = EnumSet.of(PENDING);
    ArrayDeque<OrderState> queue = new ArrayDeque<>(List.of(PENDING));
    while (!queue.isEmpty()) {
      for (OrderState n : queue.poll().next()) {
        if (seen.add(n)) {
          queue.add(n);
        }
      }
    }
    assertThat(seen).containsExactlyInAnyOrder(values());
  }

  @Property
  void everyRandomWalkReachesATerminalStateWithinTheLongestPath(
      @ForAll("choices") List<Integer> choices) {
    OrderState s = PENDING;
    int steps = 0;
    for (int choice : choices) {
      if (s.isTerminal() && s != COMPLETED) {
        break;
      }
      List<OrderState> options = List.copyOf(s.next());
      if (options.isEmpty()) {
        break;
      }
      s = options.get(Math.floorMod(choice, options.size()));
      steps++;
    }
    // The graph is acyclic, so no walk is longer than the number of states.
    assertThat(steps).isLessThan(values().length);
    if (s.next().isEmpty()) {
      assertThat(s.isTerminal()).isTrue();
    }
  }

  @Provide
  Arbitrary<List<Integer>> choices() {
    return Arbitraries.integers().between(0, 1000).list().ofMinSize(20).ofMaxSize(20);
  }
}
