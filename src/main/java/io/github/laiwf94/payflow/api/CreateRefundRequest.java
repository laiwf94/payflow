package io.github.laiwf94.payflow.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * {@code POST /v1/orders/{id}/refunds}: exactly one of {@code amount}, in minor units of the
 * order's currency, or {@code lines}, the goods given back, whose share the server prices from the
 * order.
 */
public record CreateRefundRequest(
    @Min(1) @Max(99_999_999) Long amount,
    @Size(min = 1, max = 50) List<@NotNull @Valid Line> lines) {

  public record Line(
      @NotNull
          @Pattern(regexp = "[A-Za-z0-9._-]{1,64}", message = "must be 1-64 of A-Z a-z 0-9 . _ -")
          String sku,
      @NotNull @Min(1) @Max(1000) Integer quantity) {}
}
