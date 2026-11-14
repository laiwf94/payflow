package io.github.laiwf94.payflow.api;

import io.github.laiwf94.payflow.order.OrderFilter;
import io.github.laiwf94.payflow.order.OrderState;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The query string of {@code GET /v1/orders}, read as strictly as a request body: an unknown
 * parameter, a repeated one (other than {@code state}) or a malformed value is a 400, because a
 * typo such as {@code status=FAILED} would otherwise quietly list every order.
 */
record OrderListRequest(OrderFilter filter, int limit, String cursor) {

  static final int DEFAULT_LIMIT = 20;
  static final int MAX_LIMIT = 100;

  private static final Set<String> KNOWN =
      Set.of(
          "limit",
          "cursor",
          "state",
          "created_gte",
          "created_lt",
          "currency",
          "merchant_reference");
  private static final Pattern LIMIT = Pattern.compile("[0-9]{1,3}");
  private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");
  private static final Pattern REFERENCE = Pattern.compile("[!-~]{1,128}");
  private static final Pattern COMMA = Pattern.compile(",");

  static OrderListRequest parse(Map<String, String[]> params) {
    for (String name : params.keySet()) {
      if (!KNOWN.contains(name)) {
        throw new IllegalArgumentException(
            "unknown query parameter " + printable(name) + "; known: " + String.join(", ", KNOWN));
      }
    }
    String limitText = single(params, "limit");
    int limit = DEFAULT_LIMIT;
    if (limitText != null) {
      if (!LIMIT.matcher(limitText).matches()
          || (limit = Integer.parseInt(limitText)) < 1
          || limit > MAX_LIMIT) {
        throw new IllegalArgumentException("limit: must be an integer from 1 to " + MAX_LIMIT);
      }
    }
    Set<OrderState> states = EnumSet.noneOf(OrderState.class);
    for (String value : params.getOrDefault("state", new String[0])) {
      for (String name : COMMA.split(value, -1)) {
        try {
          states.add(OrderState.valueOf(name));
        } catch (IllegalArgumentException e) {
          throw new IllegalArgumentException("state: unknown state " + printable(name), e);
        }
      }
    }
    String currency = single(params, "currency");
    if (currency != null && !CURRENCY.matcher(currency).matches()) {
      throw new IllegalArgumentException("currency: must be an ISO 4217 code such as EUR");
    }
    String reference = single(params, "merchant_reference");
    if (reference != null && !REFERENCE.matcher(reference).matches()) {
      throw new IllegalArgumentException(
          "merchant_reference: must be 1-128 printable ASCII characters without spaces");
    }
    String cursor = single(params, "cursor");
    return new OrderListRequest(
        new OrderFilter(
            states,
            instant(params, "created_gte"),
            instant(params, "created_lt"),
            currency,
            reference),
        limit,
        cursor);
  }

  static String single(Map<String, String[]> params, String name) {
    String[] values = params.get(name);
    if (values == null) {
      return null;
    }
    if (values.length > 1) {
      throw new IllegalArgumentException(name + ": give it at most once");
    }
    return values[0];
  }

  private static Instant instant(Map<String, String[]> params, String name) {
    String text = single(params, name);
    return text == null ? null : time(name, text);
  }

  /**
   * An RFC 3339 time with an offset, rounded up to the microsecond the database stores. Rounding up
   * keeps both bounds exact: for whole microseconds, {@code t >= 1.5} is {@code t >= 2} and {@code
   * t < 1.5} is {@code t < 2}.
   */
  static Instant time(String name, String text) {
    Instant t;
    try {
      t = OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
    } catch (DateTimeParseException e) {
      throw new IllegalArgumentException(
          name + ": must be an RFC 3339 time with an offset, such as 2026-11-02T09:30:00Z", e);
    }
    Instant micros = t.truncatedTo(ChronoUnit.MICROS);
    return micros.equals(t) ? t : micros.plus(1, ChronoUnit.MICROS);
  }

  /** Echoes client text in an error message only after replacing anything unprintable. */
  static String printable(String s) {
    String cut = s.length() > 64 ? s.substring(0, 64) + "..." : s;
    return "'" + cut.replaceAll("[^ -~]", "?") + "'";
  }
}
