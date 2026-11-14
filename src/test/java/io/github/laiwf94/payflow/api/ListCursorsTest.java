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

class ListCursorsTest {

  private static final String SECRET = "cursor-secret-for-tests-0123456789";
  private static final String OTHER_SECRET = "another-cursor-secret-0123456789ab";
  private static final OrderPosition AT =
      new OrderPosition(Instant.parse("2026-11-02T09:30:00.123456Z"), UUID.randomUUID());
  private static final OrderFilter EUR = new OrderFilter(Set.of(), null, null, "EUR", null);

  private final ListCursors cursors = ListCursors.of(SECRET);

  @Test
  void roundTripsAPositionAsAnOpaqueUrlSafeString() {
    String cursor = cursors.encode("acme", EUR, AT);
    assertThat(cursor).hasSize(55).matches("[A-Za-z0-9_-]+");
    assertThat(cursors.decode("acme", EUR, cursor)).isEqualTo(AT);
    assertThat(ListCursors.of(SECRET).decode("acme", EUR, cursor))
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
        .isInstanceOf(ListCursors.InvalidCursor.class);
    assertThatThrownBy(() -> cursors.decode("acme", OrderFilter.ALL, cursor))
        .as("filter dropped")
        .isInstanceOf(ListCursors.InvalidCursor.class);
    assertThatThrownBy(
            () ->
                cursors.decode(
                    "acme",
                    new OrderFilter(Set.of(OrderState.FAILED), null, null, "EUR", null),
                    cursor))
        .as("filter added")
        .isInstanceOf(ListCursors.InvalidCursor.class);
    assertThatThrownBy(() -> ListCursors.of(OTHER_SECRET).decode("acme", EUR, cursor))
        .as("another secret")
        .isInstanceOf(ListCursors.InvalidCursor.class);
  }

  @Test
  void orderCursorsAreSignedAsBeforeTheListingsWereSplit() throws Exception {
    // The tag computed independently, the way the order listing has signed since it shipped, so
    // cursors handed out before an upgrade still work after it.
    String cursor = cursors.encode("acme", EUR, AT);
    byte[] raw = java.util.Base64.getUrlDecoder().decode(cursor);
    javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
    mac.init(
        new javax.crypto.spec.SecretKeySpec(
            SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
    mac.update(
        ("payflow orders cursor\nacme\n" + EUR.canonical() + "\n")
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    byte[] tag = mac.doFinal(java.util.Arrays.copyOf(raw, 25));
    assertThat(java.util.Arrays.copyOfRange(raw, 25, 41))
        .isEqualTo(java.util.Arrays.copyOf(tag, 16));
  }

  @Test
  void aCursorBelongsToOneListingAndScope() {
    UUID endpoint = UUID.randomUUID();
    ListCursors.Position at = new ListCursors.Position(AT.createdAt(), AT.id());
    String scope = endpoint + "\nstates=FAILED";
    String deliveries = cursors.encode(ListCursors.Listing.WEBHOOK_DELIVERIES, "acme", scope, at);
    assertThat(cursors.decode(ListCursors.Listing.WEBHOOK_DELIVERIES, "acme", scope, deliveries))
        .isEqualTo(at);
    assertThatThrownBy(() -> cursors.decode(ListCursors.Listing.ORDERS, "acme", scope, deliveries))
        .as("the same bytes in another listing")
        .isInstanceOf(ListCursors.InvalidCursor.class);
    assertThatThrownBy(
            () ->
                cursors.decode(
                    ListCursors.Listing.WEBHOOK_DELIVERIES,
                    "acme",
                    UUID.randomUUID() + "\nstates=FAILED",
                    deliveries))
        .as("another endpoint")
        .isInstanceOf(ListCursors.InvalidCursor.class);
    String orders = cursors.encode("acme", EUR, AT);
    assertThatThrownBy(
            () ->
                cursors.decode(
                    ListCursors.Listing.WEBHOOK_DELIVERIES, "acme", EUR.canonical(), orders))
        .as("an order cursor in the delivery log")
        .isInstanceOf(ListCursors.InvalidCursor.class);
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
            .isInstanceOf(ListCursors.InvalidCursor.class);
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
          .isInstanceOf(ListCursors.InvalidCursor.class)
          .hasMessageContaining("start again without one");
    }
  }

  @Test
  void aSecondSecretVerifiesButOnlyTheFirstSigns() {
    String oldCursor = ListCursors.of(OTHER_SECRET).encode("acme", EUR, AT);
    ListCursors rotating = ListCursors.of(SECRET + " , " + OTHER_SECRET);
    assertThat(rotating.decode("acme", EUR, oldCursor)).isEqualTo(AT);
    String newCursor = rotating.encode("acme", EUR, AT);
    assertThat(cursors.decode("acme", EUR, newCursor)).as("signed with the first").isEqualTo(AT);
    assertThatThrownBy(() -> ListCursors.of(OTHER_SECRET).decode("acme", EUR, newCursor))
        .isInstanceOf(ListCursors.InvalidCursor.class);
  }

  @Test
  void shortSecretsAreRefusedAtStartup() {
    assertThatThrownBy(() -> ListCursors.of("too-short"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("at least 32");
    assertThatThrownBy(() -> ListCursors.of(SECRET + ","))
        .as("an empty entry")
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void withoutASecretEachInstanceSignsWithItsOwn() {
    ListCursors a = ListCursors.of("");
    ListCursors b = ListCursors.of(null);
    String cursor = a.encode("acme", EUR, AT);
    assertThat(a.decode("acme", EUR, cursor)).isEqualTo(AT);
    assertThatThrownBy(() -> b.decode("acme", EUR, cursor))
        .isInstanceOf(ListCursors.InvalidCursor.class);
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
    assertThat(ListCursors.of(SECRET + "," + OTHER_SECRET).toString())
        .doesNotContain(SECRET)
        .doesNotContain(OTHER_SECRET)
        .contains("2 secrets");
  }
}
