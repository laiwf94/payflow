package io.github.laiwf94.payflow.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * {@code POST /v1/orders}. Unit amounts are minor units of {@code currency}; the server computes
 * the total, so a client can't send a total that disagrees with its lines.
 */
public record CreateOrderRequest(
    @NotNull @Size(min = 1, max = 50) List<@NotNull @Valid Item> items,
    @NotNull @Pattern(regexp = "[A-Z]{3}", message = "must be a three-letter ISO 4217 code")
        String currency,
    @NotNull @Valid Address shippingAddress,
    @NotNull @Pattern(regexp = "pm_[a-z0-9_]{1,64}", message = "must look like pm_card_ok")
        String paymentMethod) {

  /** Free text may hold anything printable, including markup, but no control characters. */
  static final String TEXT = "[^\\p{Cntrl}]*";

  public record Item(
      @NotNull
          @Pattern(regexp = "[A-Za-z0-9._-]{1,64}", message = "must be 1-64 of A-Z a-z 0-9 . _ -")
          String sku,
      @NotNull @Min(1) @Max(1000) Integer quantity,
      @NotNull @Min(1) @Max(99_999_999) Long unitAmount) {}

  public record Address(
      @NotBlank
          @Size(max = 200)
          @Pattern(regexp = TEXT, message = "must not contain control characters")
          String name,
      @NotBlank
          @Size(max = 200)
          @Pattern(regexp = TEXT, message = "must not contain control characters")
          String line1,
      @Size(max = 200) @Pattern(regexp = TEXT, message = "must not contain control characters")
          String line2,
      @NotBlank
          @Size(max = 100)
          @Pattern(regexp = TEXT, message = "must not contain control characters")
          String city,
      @NotNull
          @Pattern(
              regexp = "[A-Za-z0-9 -]{1,20}",
              message = "must be 1-20 letters, digits, spaces or dashes")
          String postalCode,
      @NotNull @Pattern(regexp = "[A-Z]{2}", message = "must be an ISO 3166-1 alpha-2 code")
          String country) {}
}
