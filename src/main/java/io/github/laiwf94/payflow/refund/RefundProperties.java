package io.github.laiwf94.payflow.refund;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Refund settings ({@code payflow.refunds.*}). The worker itself runs on the saga's settings.
 *
 * @param allocationPolicy how a refund of an order paid several ways is split between its tenders
 *     ({@code reverse_order}, {@code proportional} or {@code card_first}). Each refund records the
 *     policy it was split by, so changing this affects only refunds created afterwards.
 */
@ConfigurationProperties("payflow.refunds")
public record RefundProperties(@DefaultValue("reverse_order") AllocationPolicy allocationPolicy) {

  public RefundProperties {
    if (allocationPolicy == null) {
      allocationPolicy = AllocationPolicy.REVERSE_ORDER;
    }
  }
}
