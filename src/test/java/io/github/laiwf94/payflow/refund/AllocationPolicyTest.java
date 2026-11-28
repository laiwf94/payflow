package io.github.laiwf94.payflow.refund;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import io.github.laiwf94.payflow.refund.AllocationPolicy.Balance;
import io.github.laiwf94.payflow.refund.AllocationPolicy.Share;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;

/** The three ways a refund is split between an order's tenders. */
class AllocationPolicyTest {

  private static final UUID GIFT = new UUID(0, 1);
  private static final UUID CARD = new UUID(0, 2);
  private static final UUID VOUCHER = new UUID(0, 3);

  /** A gift card paid 10.00, a card 15.00 and a voucher 5.00, all of it still refundable. */
  private static final List<Balance> THREE =
      List.of(
          new Balance(GIFT, 1, "pm_giftcard_ok", 1000),
          new Balance(CARD, 2, "pm_card_ok", 1500),
          new Balance(VOUCHER, 3, "pm_voucher_ok", 500));

  private static List<Tuple> split(AllocationPolicy p, long amount, List<Balance> tenders) {
    return p.allocate(amount, tenders).stream()
        .map(s -> tuple(s.position(), s.amountMinor()))
        .toList();
  }

  @Test
  void reverseOrderTakesFromTheLastTenderFirst() {
    assertThat(split(AllocationPolicy.REVERSE_ORDER, 400, THREE)).containsExactly(tuple(3, 400L));
    assertThat(split(AllocationPolicy.REVERSE_ORDER, 1200, THREE))
        .containsExactly(tuple(3, 500L), tuple(2, 700L));
    assertThat(split(AllocationPolicy.REVERSE_ORDER, 3000, THREE))
        .containsExactly(tuple(3, 500L), tuple(2, 1500L), tuple(1, 1000L));
  }

  @Test
  void cardFirstReturnsToCardsBeforeStoredValue() {
    assertThat(split(AllocationPolicy.CARD_FIRST, 1200, THREE)).containsExactly(tuple(2, 1200L));
    assertThat(split(AllocationPolicy.CARD_FIRST, 2200, THREE))
        .as("then the others, last first")
        .containsExactly(tuple(2, 1500L), tuple(3, 500L), tuple(1, 200L));
    List<Balance> twoCards =
        List.of(
            new Balance(GIFT, 1, "pm_card_ok", 300),
            new Balance(CARD, 2, "pm_giftcard_ok", 300),
            new Balance(VOUCHER, 3, "pm_card_visa", 300));
    assertThat(split(AllocationPolicy.CARD_FIRST, 700, twoCards))
        .containsExactly(tuple(3, 300L), tuple(1, 300L), tuple(2, 100L));
  }

  @Test
  void proportionalSplitsByWhatEachTenderCanReturn() {
    assertThat(split(AllocationPolicy.PROPORTIONAL, 1500, THREE))
        .as("half of each, made last first")
        .containsExactly(tuple(3, 250L), tuple(2, 750L), tuple(1, 500L));
    assertThat(split(AllocationPolicy.PROPORTIONAL, 3000, THREE))
        .as("everything left returns each rest exactly")
        .containsExactly(tuple(3, 500L), tuple(2, 1500L), tuple(1, 1000L));
    // 100 over 1:1:1 is 33.33 each; the leftover unit goes to the later tender on a tie.
    List<Balance> even =
        List.of(
            new Balance(GIFT, 1, "pm_giftcard_ok", 300),
            new Balance(CARD, 2, "pm_card_ok", 300),
            new Balance(VOUCHER, 3, "pm_voucher_ok", 300));
    assertThat(split(AllocationPolicy.PROPORTIONAL, 100, even))
        .containsExactly(tuple(3, 34L), tuple(2, 33L), tuple(1, 33L));
    // 1 unit over 1000:1500:500 goes to the largest remainder, the card, and nobody else.
    assertThat(split(AllocationPolicy.PROPORTIONAL, 1, THREE)).containsExactly(tuple(2, 1L));
  }

  @Test
  void tendersWithNothingLeftAreSkipped() {
    List<Balance> spent =
        List.of(
            new Balance(GIFT, 1, "pm_giftcard_ok", 1000),
            new Balance(CARD, 2, "pm_card_ok", 0),
            new Balance(VOUCHER, 3, "pm_voucher_ok", 0));
    for (AllocationPolicy p : AllocationPolicy.values()) {
      assertThat(split(p, 600, spent)).as("%s", p).containsExactly(tuple(1, 600L));
    }
  }

  @Test
  void refusesWhatTheTendersCannotReturn() {
    for (AllocationPolicy p : AllocationPolicy.values()) {
      assertThatThrownBy(() -> p.allocate(3001, THREE))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("refund of 3001 exceeds the 3000 the tenders can return");
      assertThatThrownBy(() -> p.allocate(0, THREE)).isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> p.allocate(1, List.of()))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(
              () ->
                  p.allocate(
                      1,
                      List.of(
                          new Balance(GIFT, 1, "pm_card_ok", 10),
                          new Balance(CARD, 1, "pm_card_ok", 10))))
          .hasMessageContaining("positions repeat");
    }
    assertThatThrownBy(() -> new Balance(GIFT, 1, "pm_card_ok", -1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void codesRoundTrip() {
    for (AllocationPolicy p : AllocationPolicy.values()) {
      assertThat(AllocationPolicy.fromCode(p.code())).isEqualTo(p);
    }
    assertThat(AllocationPolicy.CARD_FIRST.code()).isEqualTo("card_first");
    assertThatThrownBy(() -> AllocationPolicy.fromCode("cheapest_first"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(AllocationPolicy.isCard("pm_card_ok")).isTrue();
    assertThat(AllocationPolicy.isCard("pm_giftcard_ok")).isFalse();
    assertThat(AllocationPolicy.isCard(null)).isFalse();
  }

  record Case(List<Balance> tenders, long amount) {}

  @Provide
  Arbitrary<Case> cases() {
    Arbitrary<String> method =
        Arbitraries.of("pm_card_ok", "pm_giftcard_ok", "pm_voucher_ok", "pm_card_visa");
    Arbitrary<Long> balance =
        Arbitraries.oneOf(
            Arbitraries.longs().between(0, 10),
            Arbitraries.longs().between(0, 5_000),
            Arbitraries.longs().between(0, 99_999_999));
    return Combinators.combine(method.list().ofMinSize(1).ofMaxSize(16), balance.list().ofSize(16))
        .as(
            (methods, balances) -> {
              List<Balance> out = new ArrayList<>();
              for (int i = 0; i < methods.size(); i++) {
                out.add(new Balance(new UUID(0, i + 1), i + 1, methods.get(i), balances.get(i)));
              }
              return out;
            })
        .filter(ts -> ts.stream().mapToLong(Balance::refundableMinor).sum() > 0)
        .flatMap(
            ts ->
                Arbitraries.longs()
                    .between(1, ts.stream().mapToLong(Balance::refundableMinor).sum())
                    .map(a -> new Case(ts, a)));
  }

  /**
   * Whatever the policy and the tenders: the whole amount is given out, no tender gets more than it
   * can return or appears twice, none gets nothing, and the policy's order is kept.
   */
  @Property(tries = 2_000)
  void everyPolicyGivesOutExactlyTheAmountWithinEachTender(
      @ForAll("cases") Case c, @ForAll AllocationPolicy policy) {
    List<Share> shares = policy.allocate(c.amount(), c.tenders());
    Map<Integer, Balance> byPosition = new HashMap<>();
    c.tenders().forEach(b -> byPosition.put(b.position(), b));
    assertThat(shares.stream().mapToLong(Share::amountMinor).sum()).isEqualTo(c.amount());
    assertThat(shares).extracting(Share::position).doesNotHaveDuplicates();
    for (Share s : shares) {
      Balance b = byPosition.get(s.position());
      assertThat(s.paymentId()).isEqualTo(b.paymentId());
      assertThat(s.amountMinor()).isPositive().isLessThanOrEqualTo(b.refundableMinor());
    }
    switch (policy) {
      case REVERSE_ORDER -> {
        assertThat(shares).extracting(Share::position).isSortedAccordingTo((a, b) -> b - a);
        // Only the last tender used may be left with something.
        for (int i = 0; i < shares.size() - 1; i++) {
          Share s = shares.get(i);
          assertThat(s.amountMinor()).isEqualTo(byPosition.get(s.position()).refundableMinor());
        }
      }
      case CARD_FIRST -> {
        boolean seenOther = false;
        for (Share s : shares) {
          boolean card = AllocationPolicy.isCard(byPosition.get(s.position()).method());
          assertThat(card && seenOther).as("a card after a non-card in %s", shares).isFalse();
          seenOther |= !card;
        }
      }
      case PROPORTIONAL -> {
        long total = c.tenders().stream().mapToLong(Balance::refundableMinor).sum();
        for (Balance b : c.tenders()) {
          long got =
              shares.stream()
                  .filter(s -> s.position() == b.position())
                  .mapToLong(Share::amountMinor)
                  .sum();
          // The exact proportion rounded down, or one more (exact arithmetic: both fit a long).
          long floor = c.amount() * b.refundableMinor() / total;
          assertThat(got).isBetween(floor, floor + 1);
        }
      }
    }
  }
}
