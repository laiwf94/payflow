package io.github.laiwf94.payflow.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.laiwf94.payflow.order.OrderFilter;
import io.github.laiwf94.payflow.order.OrderState;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OrderListRequestTest {

  private static OrderListRequest parse(String... pairs) {
    Map<String, String[]> params = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      String[] old = params.getOrDefault(pairs[i], new String[0]);
      String[] values = java.util.Arrays.copyOf(old, old.length + 1);
      values[old.length] = pairs[i + 1];
      params.put(pairs[i], values);
    }
    return OrderListRequest.parse(params);
  }

  private static void refused(String message, String... pairs) {
    assertThatThrownBy(() -> parse(pairs))
        .as(String.join("&", pairs))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(message);
  }

  @Test
  void noParametersListEverythingTwentyAtATime() {
    OrderListRequest r = parse();
    assertThat(r.filter()).isEqualTo(OrderFilter.ALL);
    assertThat(r.limit()).isEqualTo(20);
    assertThat(r.cursor()).isNull();
  }

  @Test
  void readsEveryFilter() {
    OrderListRequest r =
        parse(
            "limit", "100",
            "cursor", "abc",
            "state", "PENDING,FAILED",
            "state", "COMPLETED",
            "created_gte", "2026-11-01T00:00:00Z",
            "created_lt", "2026-11-02T01:00:00+01:00",
            "currency", "JPY",
            "merchant_reference", "cart-1");
    assertThat(r.limit()).isEqualTo(100);
    assertThat(r.cursor()).isEqualTo("abc");
    assertThat(r.filter())
        .isEqualTo(
            new OrderFilter(
                Set.of(OrderState.PENDING, OrderState.FAILED, OrderState.COMPLETED),
                Instant.parse("2026-11-01T00:00:00Z"),
                Instant.parse("2026-11-02T00:00:00Z"),
                "JPY",
                "cart-1"));
  }

  @Test
  void boundsBelowAMicrosecondRoundUpSoBothStayExact() {
    OrderListRequest r =
        parse(
            "created_gte", "2026-11-01T00:00:00.0000015Z",
            "created_lt", "2026-11-01T00:00:00.000003Z");
    assertThat(r.filter().createdFrom()).isEqualTo(Instant.parse("2026-11-01T00:00:00.000002Z"));
    assertThat(r.filter().createdBefore()).isEqualTo(Instant.parse("2026-11-01T00:00:00.000003Z"));
  }

  @Test
  void refusesWhatItDoesNotUnderstand() {
    refused("unknown query parameter 'status'", "status", "FAILED");
    refused("unknown query parameter '?x'", "\u0007x", "1");
    refused("limit", "limit", "0");
    refused("limit", "limit", "101");
    refused("limit", "limit", "-1");
    refused("limit", "limit", "1.5");
    refused("limit", "limit", "");
    refused("limit", "limit", "99999999999");
    refused("limit: give it at most once", "limit", "5", "limit", "6");
    refused("state: unknown state 'pending'", "state", "pending");
    refused("state: unknown state ''", "state", "PENDING,");
    refused("currency", "currency", "eur");
    refused("currency", "currency", "EURO");
    refused("merchant_reference", "merchant_reference", "has space");
    refused("merchant_reference", "merchant_reference", "x".repeat(129));
    refused("merchant_reference", "merchant_reference", "");
    refused("created_gte: must be an RFC 3339", "created_gte", "2026-11-01");
    refused("created_lt: must be an RFC 3339", "created_lt", "2026-11-01T00:00:00");
    refused("created_gte: must be an RFC 3339", "created_gte", "yesterday");
    refused(
        "created_gte must be before created_lt",
        "created_gte",
        "2026-11-02T00:00:00Z",
        "created_lt",
        "2026-11-02T00:00:00Z");
    refused("cursor: give it at most once", "cursor", "a", "cursor", "b");
  }

  @Test
  void aVeryLongUnknownNameIsCutInTheMessage() {
    assertThatThrownBy(() -> parse("y".repeat(500), "1"))
        .hasMessageContaining("y".repeat(64) + "...'")
        .hasMessageNotContaining("y".repeat(65));
  }
}
