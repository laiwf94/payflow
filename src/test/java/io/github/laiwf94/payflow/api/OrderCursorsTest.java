package io.github.laiwf94.payflow.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.laiwf94.payflow.order.OrderFilter;
import io.github.laiwf94.payflow.order.OrderPosition;
import io.github.laiwf94.payflow.order.OrderState;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderCursorsTest {

  private static final String SECRET = "cursor-secret-for-tests-0123456789";
  private static final String OTHER_SECRET = "another-cursor-secret-0123456789ab";
  private static final OrderPosition AT =
      new OrderPosition(Instant.parse("2026-11-02T09:30:00.123456Z"), UUID.randomUUID());
  private static final OrderFilter EUR = new OrderFilter(Set.of(), null, null, "EUR", null);

  private final OrderCursors cursors = OrderCursors.of(SECRET);

  @Test
  void roundTripsAPositionAsAnOpaqueUrlSafeString() {
    String cursor = cursors.encode("acme", EUR, AT);
    assertThat(cursor).hasSize(55).matches("[A-Za-z0-9_-]+");
    assertThat(cursors.decode("acme", EUR, cursor)).isEqualTo(AT);
    assertThat(OrderCursors.of(SECRET).decode("acme", EUR, cursor))
        .as("another instance with the same secret")
        .isEqualTo(AT);
  }

  @Test
  void positionsBeforeTheEpochAndAtTheExtremesRoundTrip() {
    for (Instant t :
        new Instant[] {
          Instant.parse("1969-12-31T23:59:59.999999Z"),
          Instant.EPOCH,
          Instant.parse("9999-12-31T23:59:59.999999Z")
        }) {
      OrderPosition p = new OrderPosition(t, new UUID(-1L, Long.MIN_VALUE));
      assertThat(
              cursors.decode("acme", OrderFilter.ALL, cursors.encode("acme", OrderFilter.ALL, p)))
          .isEqualTo(p);
    }
  }

  @Test
  void aCursorOnlyContinuesTheListingItCameFrom() {
    String cursor = cursors.encode("acme", EUR, AT);
    assertThatThrownBy(() -> cursors.decode("globex", EUR, cursor))
        .as("another merchant")
        .isInstanceOf(OrderCursors.InvalidCursor.class);
    assertThatThrownBy(() -> cursors.decode("acme", OrderFilter.ALL, cursor))
        .as("filter dropped")
        .isInstanceOf(OrderCursors.InvalidCursor.class);
    assertThatThrownBy(
            () ->
                cursors.decode(
                    "acme",
                    new OrderFilter(Set.of(OrderState.FAILED), null, null, "EUR", null),
                    cursor))
        .as("filter added")
        .isInstanceOf(OrderCursors.InvalidCursor.class);
    assertThatThrownBy(() -> OrderCursors.of(OTHER_SECRET).decode("acme", EUR, cursor))
        .as("another secret")
        .isInstanceOf(OrderCursors.InvalidCursor.class);
  }

  @Test
  void theStatesInAFilterAreSignedWhateverOrderTheyCameIn() {
    OrderFilter ab =
        new OrderFilter(Set.of(OrderState.FAILED, OrderState.PENDING), null, null, null, null);
    OrderFilter ba =
        new OrderFilter(Set.of(OrderState.PENDING, OrderState.FAILED), null, null, null, null);
    assertThat(cursors.decode("acme", ba, cursors.encode("acme", ab, AT))).isEqualTo(AT);
  }

  @Test
  void changingAnyByteOfTheCursorIsRefused() {
    byte[] raw = Base64.getUrlDecoder().decode(cursors.encode("acme", EUR, AT));
    for (int i = 0; i < raw.length; i++) {
      for (int bit = 0; bit < 8; bit++) {
        byte[] forged = raw.clone();
        forged[i] ^= (byte) (1 << bit);
        String cursor = Base64.getUrlEncoder().withoutPadding().encodeToString(forged);
        assertThatThrownBy(() -> cursors.decode("acme", EUR, cursor))
            .as("byte %d bit %d", i, bit)
            .isInstanceOf(OrderCursors.InvalidCursor.class);
      }
    }
  }

  @Test
  void malformedCursorsAreRefused() {
    String good = cursors.encode("acme", EUR, AT);
    for (String bad :
        new String[] {
          "",
          "x",
          good.substring(1),
          good + "A",
          good.substring(0, 54) + "=",
          good.substring(0, 54) + "+",
          good.substring(0, 54) + "/",
          "!".repeat(55),
          good.replace(good.charAt(0), 'é')
        }) {
      assertThatThrownBy(() -> cursors.decode("acme", EUR, bad))
          .as(bad)
          .isInstanceOf(OrderCursors.InvalidCursor.class)
          .hasMessageContaining("start again without one");
    }
  }

  @Test
  void aSecondSecretVerifiesButOnlyTheFirstSigns() {
    String oldCursor = OrderCursors.of(OTHER_SECRET).encode("acme", EUR, AT);
    OrderCursors rotating = OrderCursors.of(SECRET + " , " + OTHER_SECRET);
    assertThat(rotating.decode("acme", EUR, oldCursor)).isEqualTo(AT);
    String newCursor = rotating.encode("acme", EUR, AT);
    assertThat(cursors.decode("acme", EUR, newCursor)).as("signed with the first").isEqualTo(AT);
    assertThatThrownBy(() -> OrderCursors.of(OTHER_SECRET).decode("acme", EUR, newCursor))
        .isInstanceOf(OrderCursors.InvalidCursor.class);
  }

  @Test
  void shortSecretsAreRefusedAtStartup() {
    assertThatThrownBy(() -> OrderCursors.of("too-short"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("at least 32");
    assertThatThrownBy(() -> OrderCursors.of(SECRET + ","))
        .as("an empty entry")
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void withoutASecretEachInstanceSignsWithItsOwn() {
    OrderCursors a = OrderCursors.of("");
    OrderCursors b = OrderCursors.of(null);
    String cursor = a.encode("acme", EUR, AT);
    assertThat(a.decode("acme", EUR, cursor)).isEqualTo(AT);
    assertThatThrownBy(() -> b.decode("acme", EUR, cursor))
        .isInstanceOf(OrderCursors.InvalidCursor.class);
  }

  @Test
  void onlyMicrosecondPositionsAreEncoded() {
    OrderPosition nanos =
        new OrderPosition(Instant.parse("2026-11-02T09:30:00.123456789Z"), UUID.randomUUID());
    assertThatThrownBy(() -> cursors.encode("acme", EUR, nanos))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theSecretsStayOutOfToString() {
    assertThat(OrderCursors.of(SECRET + "," + OTHER_SECRET).toString())
        .doesNotContain(SECRET)
        .doesNotContain(OTHER_SECRET)
        .contains("2 secrets");
  }
}
