package io.github.laiwf94.payflow.money;

import java.util.Currency;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * An amount in the smallest unit of an ISO 4217 currency: 1999 EUR is 19.99 euro, 500 JPY is 500
 * yen. Floating point and BigDecimal never appear; arithmetic is exact or throws.
 *
 * <p>Amounts are strictly positive and at most {@link #MAX_MINOR_UNITS}, the eight-digit ceiling
 * card processors commonly accept for a single charge.
 */
public record Money(long minorUnits, Currency currency) {

  public static final long MAX_MINOR_UNITS = 99_999_999L;

  private static final Pattern CODE = Pattern.compile("[A-Z]{3}");

  public Money {
    Objects.requireNonNull(currency, "currency");
    if (minorUnits <= 0) {
      throw new InvalidMoneyException("amount must be positive");
    }
    if (minorUnits > MAX_MINOR_UNITS) {
      throw new InvalidMoneyException("amount must be at most " + MAX_MINOR_UNITS);
    }
    if (currency.getDefaultFractionDigits() < 0) {
      throw new InvalidMoneyException("currency " + currency + " is not a spendable currency");
    }
  }

  public static Money of(long minorUnits, String currencyCode) {
    return new Money(minorUnits, currency(currencyCode));
  }

  /**
   * Parses an ISO 4217 code. Codes without a minor unit definition (gold, test and fund codes such
   * as XAU, XTS, XXX) are rejected because they can't be charged.
   */
  public static Currency currency(String code) {
    if (code == null || !CODE.matcher(code).matches()) {
      throw new InvalidMoneyException("currency must be a three-letter ISO 4217 code");
    }
    Currency c;
    try {
      c = Currency.getInstance(code);
    } catch (IllegalArgumentException e) {
      throw new InvalidMoneyException("unknown currency " + code);
    }
    if (c.getDefaultFractionDigits() < 0) {
      throw new InvalidMoneyException("currency " + code + " is not a spendable currency");
    }
    return c;
  }

  public Money plus(Money other) {
    requireSameCurrency(other);
    return new Money(checked(() -> Math.addExact(minorUnits, other.minorUnits)), currency);
  }

  public Money times(int quantity) {
    if (quantity <= 0) {
      throw new InvalidMoneyException("quantity must be positive");
    }
    return new Money(checked(() -> Math.multiplyExact(minorUnits, (long) quantity)), currency);
  }

  public String currencyCode() {
    return currency.getCurrencyCode();
  }

  private void requireSameCurrency(Money other) {
    if (!currency.equals(other.currency)) {
      throw new InvalidMoneyException("currency mismatch: " + currency + " and " + other.currency);
    }
  }

  private static long checked(java.util.function.LongSupplier op) {
    try {
      return op.getAsLong();
    } catch (ArithmeticException e) {
      throw new InvalidMoneyException("amount must be at most " + MAX_MINOR_UNITS);
    }
  }

  @Override
  public String toString() {
    return minorUnits + " " + currency.getCurrencyCode();
  }
}
