package io.github.laiwf94.payflow.refund;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * How a refund of an order paid with several tenders is split between them. Every policy gives each
 * tender at most what it can still return (what it captured less what other refunds already took or
 * are taking), gives the whole amount out, and leaves out tenders that get nothing. The shares come
 * back in the order they are made in.
 *
 * <ul>
 *   <li>{@link #REVERSE_ORDER}: the last tender first, the way the saga undoes a payment, so the
 *       tender paid last, usually the card that topped up a gift card or voucher, gives back first.
 *   <li>{@link #PROPORTIONAL}: in proportion to what each tender can still return, rounding down,
 *       with the units left over going one each to the tenders with the largest remainders (ties to
 *       the later tender). Refunding everything left returns each tender's rest exactly.
 *   <li>{@link #CARD_FIRST}: card tenders first, then the others, each group last tender first, so
 *       money goes back to the customer's card before any stored value is restored.
 * </ul>
 */
public enum AllocationPolicy {
  REVERSE_ORDER,
  PROPORTIONAL,
  CARD_FIRST;

  /**
   * One tender as a refund sees it: its id, position, method and what it can still return.
   *
   * @param refundableMinor captured less what refunds that haven't failed are returning from it
   */
  public record Balance(UUID paymentId, int position, String method, long refundableMinor) {
    public Balance {
      if (refundableMinor < 0) {
        throw new IllegalArgumentException("refundable can't be negative: " + refundableMinor);
      }
    }
  }

  /** What one tender gives back of a refund. */
  public record Share(UUID paymentId, int position, long amountMinor) {}

  /** The value stored and configured, e.g. {@code card_first}. */
  public String code() {
    return name().toLowerCase(Locale.ROOT);
  }

  /**
   * The policy stored or configured as {@code code}.
   *
   * @throws IllegalArgumentException for anything else
   */
  public static AllocationPolicy fromCode(String code) {
    for (AllocationPolicy p : values()) {
      if (p.code().equals(code)) {
        return p;
      }
    }
    throw new IllegalArgumentException("unknown allocation policy: " + code);
  }

  /** True for a card method ({@code pm_card_...}); everything else is stored value or a wallet. */
  static boolean isCard(String method) {
    return method != null && method.startsWith("pm_card");
  }

  /**
   * Splits {@code amountMinor} between {@code tenders}.
   *
   * @throws IllegalArgumentException when the amount isn't positive, a position repeats, or the
   *     tenders can't return that much between them
   */
  public List<Share> allocate(long amountMinor, List<Balance> tenders) {
    if (amountMinor < 1) {
      throw new IllegalArgumentException("amount must be positive: " + amountMinor);
    }
    if (tenders.stream().map(Balance::position).distinct().count() != tenders.size()) {
      throw new IllegalArgumentException("tender positions repeat: " + tenders);
    }
    long available = tenders.stream().mapToLong(Balance::refundableMinor).sum();
    if (amountMinor > available) {
      throw new IllegalArgumentException(
          "refund of " + amountMinor + " exceeds the " + available + " the tenders can return");
    }
    Comparator<Balance> lastFirst = Comparator.comparingInt(Balance::position).reversed();
    return switch (this) {
      case REVERSE_ORDER -> inOrder(amountMinor, sorted(tenders, lastFirst));
      case CARD_FIRST ->
          inOrder(
              amountMinor,
              sorted(
                  tenders,
                  Comparator.comparingInt((Balance b) -> isCard(b.method()) ? 0 : 1)
                      .thenComparing(lastFirst)));
      case PROPORTIONAL -> proportional(amountMinor, sorted(tenders, lastFirst), available);
    };
  }

  private static List<Balance> sorted(List<Balance> tenders, Comparator<Balance> order) {
    List<Balance> out = new ArrayList<>(tenders);
    out.sort(order);
    return out;
  }

  /** Fills each tender in turn until the amount is given out. */
  private static List<Share> inOrder(long amount, List<Balance> tenders) {
    List<Share> out = new ArrayList<>();
    long left = amount;
    for (Balance b : tenders) {
      if (left == 0) {
        break;
      }
      long take = Math.min(left, b.refundableMinor());
      if (take > 0) {
        out.add(new Share(b.paymentId(), b.position(), take));
        left -= take;
      }
    }
    return List.copyOf(out);
  }

  /**
   * Largest remainder. Each floor is below the tender's balance unless the whole balance is asked
   * for, so the extra unit a remainder earns always fits.
   */
  private static List<Share> proportional(long amount, List<Balance> tenders, long available) {
    int n = tenders.size();
    long[] take = new long[n];
    long[] remainder = new long[n];
    long given = 0;
    for (int i = 0; i < n; i++) {
      long v = tenders.get(i).refundableMinor();
      // Each is at most 99,999,999, so the product fits in a long with room to spare.
      long scaled = amount * v;
      take[i] = scaled / available;
      remainder[i] = scaled % available;
      given += take[i];
    }
    long extra = amount - given;
    Integer[] byRemainder = new Integer[n];
    for (int i = 0; i < n; i++) {
      byRemainder[i] = i;
    }
    // Tenders are last first, so a stable sort breaks ties towards the later tender.
    Arrays.sort(byRemainder, (a, b) -> Long.compare(remainder[b], remainder[a]));
    for (int k = 0; k < n && extra > 0; k++) {
      int i = byRemainder[k];
      if (remainder[i] > 0) {
        take[i]++;
        extra--;
      }
    }
    if (extra != 0) {
      throw new IllegalStateException("largest remainder left " + extra + " unallocated");
    }
    List<Share> out = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      if (take[i] > 0) {
        Balance b = tenders.get(i);
        out.add(new Share(b.paymentId(), b.position(), take[i]));
      }
    }
    return List.copyOf(out);
  }
}
