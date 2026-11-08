package io.github.laiwf94.payflow.refund;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.laiwf94.payflow.order.LineItem;
import java.util.List;
import org.junit.jupiter.api.Test;

/** How a line refund is priced from the order's own lines. */
class RefundShareTest {

  private static final List<LineItem> ITEMS =
      List.of(
          new LineItem("mug", 2, 1000), new LineItem("tee", 1, 2500), new LineItem("mug", 3, 800));

  @Test
  void unitsComeBackFromTheFirstLineFirst() {
    assertThat(RefundService.share(ITEMS, new RefundLine("mug", 1), 0)).isEqualTo(1000);
    assertThat(RefundService.share(ITEMS, new RefundLine("mug", 2), 0)).isEqualTo(2000);
    assertThat(RefundService.share(ITEMS, new RefundLine("mug", 3), 0)).isEqualTo(2800);
    assertThat(RefundService.share(ITEMS, new RefundLine("mug", 5), 0)).isEqualTo(4400);
    assertThat(RefundService.share(ITEMS, new RefundLine("tee", 1), 0)).isEqualTo(2500);
  }

  @Test
  void unitsOtherRefundsGaveBackAreSkipped() {
    assertThat(RefundService.share(ITEMS, new RefundLine("mug", 1), 1)).isEqualTo(1000);
    assertThat(RefundService.share(ITEMS, new RefundLine("mug", 1), 2)).isEqualTo(800);
    assertThat(RefundService.share(ITEMS, new RefundLine("mug", 2), 1)).isEqualTo(1800);
    assertThat(RefundService.share(ITEMS, new RefundLine("mug", 3), 2)).isEqualTo(2400);
  }

  @Test
  void anySplitOfASkuAddsUpToTheSameTotal() {
    for (int first = 1; first < 5; first++) {
      long a = RefundService.share(ITEMS, new RefundLine("mug", first), 0);
      long b = RefundService.share(ITEMS, new RefundLine("mug", 5 - first), first);
      assertThat(a + b).as("split %d + %d", first, 5 - first).isEqualTo(4400);
    }
  }

  @Test
  void moreThanIsLeftIsRefusedWithWhatIsLeft() {
    assertThatThrownBy(() -> RefundService.share(ITEMS, new RefundLine("mug", 6), 0))
        .isInstanceOfSatisfying(
            RefundService.ExceedsPurchased.class,
            e -> {
              assertThat(e.sku()).isEqualTo("mug");
              assertThat(e.remaining()).isEqualTo(5);
            });
    assertThatThrownBy(() -> RefundService.share(ITEMS, new RefundLine("mug", 2), 4))
        .isInstanceOfSatisfying(
            RefundService.ExceedsPurchased.class, e -> assertThat(e.remaining()).isEqualTo(1));
    assertThatThrownBy(() -> RefundService.share(ITEMS, new RefundLine("tee", 1), 1))
        .isInstanceOfSatisfying(
            RefundService.ExceedsPurchased.class,
            e -> {
              assertThat(e.remaining()).isZero();
              assertThat(e.getMessage())
                  .isEqualTo("refund of 1 of tee exceeds the 0 left to refund");
            });
  }
}
