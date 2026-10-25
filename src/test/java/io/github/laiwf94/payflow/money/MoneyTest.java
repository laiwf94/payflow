package io.github.laiwf94.payflow.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Currency;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MoneyTest {

  @Test
  void acceptsBounds() {
    assertThat(Money.of(1, "EUR").minorUnits()).isEqualTo(1);
    assertThat(Money.of(Money.MAX_MINOR_UNITS, "JPY").currencyCode()).isEqualTo("JPY");
    assertThat(Money.of(1999, "EUR")).hasToString("1999 EUR");
  }

  @ParameterizedTest
  @ValueSource(longs = {0, -1, Long.MIN_VALUE, Money.MAX_MINOR_UNITS + 1, Long.MAX_VALUE})
  void rejectsAmountsOutOfRange(long amount) {
    assertThatThrownBy(() -> Money.of(amount, "EUR")).isInstanceOf(InvalidMoneyException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "eur", "EU", "EURO", "E1R", "ZZZ", "XAU", "XTS", "XXX", " EUR"})
  void rejectsCurrenciesThatCannotBeCharged(String code) {
    assertThatThrownBy(() -> Money.of(100, code)).isInstanceOf(InvalidMoneyException.class);
  }

  @Test
  void rejectsNullCurrency() {
    assertThatThrownBy(() -> Money.currency(null)).isInstanceOf(InvalidMoneyException.class);
    assertThatThrownBy(() -> new Money(1, null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new Money(1, Currency.getInstance("XAU")))
        .isInstanceOf(InvalidMoneyException.class);
  }

  @Test
  void sumsAndMultipliesExactly() {
    assertThat(Money.of(1999, "EUR").times(3).plus(Money.of(3, "EUR")))
        .isEqualTo(Money.of(6000, "EUR"));
  }

  @Test
  void refusesToMixCurrencies() {
    assertThatThrownBy(() -> Money.of(1, "EUR").plus(Money.of(1, "USD")))
        .isInstanceOf(InvalidMoneyException.class)
        .hasMessageContaining("mismatch");
  }

  @Test
  void overflowAndCapAreErrorsNotWraparound() {
    Money max = Money.of(Money.MAX_MINOR_UNITS, "EUR");
    assertThatThrownBy(() -> max.plus(Money.of(1, "EUR")))
        .isInstanceOf(InvalidMoneyException.class);
    assertThatThrownBy(() -> max.times(Integer.MAX_VALUE))
        .isInstanceOf(InvalidMoneyException.class);
    assertThatThrownBy(() -> max.times(0)).isInstanceOf(InvalidMoneyException.class);
    assertThatThrownBy(() -> new Money(Long.MAX_VALUE / 2, Currency.getInstance("EUR")))
        .isInstanceOf(InvalidMoneyException.class);
  }

  @Property
  void timesMatchesRepeatedAddition(
      @ForAll @LongRange(min = 1, max = 100_000) long unit,
      @ForAll @IntRange(min = 1, max = 50) int qty) {
    Money each = Money.of(unit, "USD");
    long expected = unit * qty;
    if (expected > Money.MAX_MINOR_UNITS) {
      assertThatThrownBy(() -> each.times(qty)).isInstanceOf(InvalidMoneyException.class);
      return;
    }
    Money sum = each;
    for (int i = 1; i < qty; i++) {
      sum = sum.plus(each);
    }
    assertThat(each.times(qty)).isEqualTo(sum);
  }
}
