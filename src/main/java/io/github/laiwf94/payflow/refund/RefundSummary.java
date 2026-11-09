package io.github.laiwf94.payflow.refund;

import java.util.List;

/**
 * What an order shows of its refunds: the {@link #RECENT} newest, newest first, whether it has
 * older ones, and how much its {@code PENDING} refunds are returning. The full list is {@code GET
 * /v1/orders/{id}/refunds}.
 */
public record RefundSummary(List<Refund> recent, boolean hasMore, long pendingMinor) {

  /** How many refunds an order embeds. */
  public static final int RECENT = 10;

  /** An order without refunds. */
  public static final RefundSummary NONE = new RefundSummary(List.of(), false, 0);

  public RefundSummary {
    recent = List.copyOf(recent);
    if (recent.size() > RECENT) {
      throw new IllegalArgumentException("at most " + RECENT + " recent refunds");
    }
  }
}
