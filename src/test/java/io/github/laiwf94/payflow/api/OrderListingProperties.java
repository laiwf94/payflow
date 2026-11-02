package io.github.laiwf94.payflow.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.laiwf94.payflow.PayflowApplication;
import io.github.laiwf94.payflow.money.Money;
import io.github.laiwf94.payflow.order.LineItem;
import io.github.laiwf94.payflow.order.Order;
import io.github.laiwf94.payflow.order.OrderFilter;
import io.github.laiwf94.payflow.order.OrderPage;
import io.github.laiwf94.payflow.order.OrderPosition;
import io.github.laiwf94.payflow.order.OrderRepository;
import io.github.laiwf94.payflow.order.OrderService;
import io.github.laiwf94.payflow.order.OrderState;
import io.github.laiwf94.payflow.order.Orders;
import io.github.laiwf94.payflow.support.Db;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.lifecycle.AfterContainer;
import net.jqwik.api.statistics.Statistics;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Paging through a merchant's orders while new orders keep arriving, against real Postgres and with
 * every page boundary going through a signed cursor, as a client would. Timestamps are drawn from a
 * handful of seconds so many orders tie on {@code created_at} and pages often end inside a tie.
 * Whatever the filters, page size and inserts, the pages together must:
 *
 * <ul>
 *   <li>repeat no order;
 *   <li>skip no order that matched before paging began (a gap);
 *   <li>contain only the merchant's orders that match the filter;
 *   <li>run strictly newest first by {@code (created_at, id)}, comparing ids as Postgres does;
 *   <li>be full except for the last, and say {@code has_more} exactly when another page follows.
 * </ul>
 */
class OrderListingProperties {

  private static final Instant BASE = Instant.parse("2026-11-02T09:00:00Z");
  private static final List<String> MERCHANTS = List.of("acme", "globex");
  private static final List<String> CURRENCIES = List.of("EUR", "USD", "JPY");
  private static final List<OrderState> STATES =
      List.of(OrderState.PENDING, OrderState.RELEASING_INVENTORY);

  /** Postgres compares uuids as 16 unsigned bytes; {@link UUID#compareTo} is signed. */
  static final Comparator<OrderPosition> NEWEST_FIRST =
      Comparator.comparing(OrderPosition::createdAt)
          .thenComparing(
              (a, b) ->
                  Long.compareUnsigned(
                      a.id().getMostSignificantBits(), b.id().getMostSignificantBits()))
          .thenComparing(
              (a, b) ->
                  Long.compareUnsigned(
                      a.id().getLeastSignificantBits(), b.id().getLeastSignificantBits()))
          .reversed();

  private static ConfigurableApplicationContext ctx;

  private static synchronized ConfigurableApplicationContext context() {
    if (ctx == null) {
      ctx =
          new SpringApplicationBuilder(PayflowApplication.class).web(WebApplicationType.NONE).run();
    }
    return ctx;
  }

  @AfterContainer
  static void close() {
    if (ctx != null) {
      ctx.close();
      ctx = null;
    }
  }

  @Property(tries = 150)
  void pagingWhileOrdersArriveHasNoGapsOrRepeats(
      @ForAll long seed,
      @ForAll @IntRange(min = 0, max = 80) int initial,
      @ForAll @IntRange(min = 1, max = 12) int pageSize,
      @ForAll @IntRange(min = 0, max = 6) int insertsPerPage) {
    JdbcClient jdbc = context().getBean(JdbcClient.class);
    OrderRepository repo = context().getBean(OrderRepository.class);
    OrderService service = context().getBean(OrderService.class);
    OrderCursors cursors = OrderCursors.of("listing-property-secret-0123456789");
    Db.truncateAll(jdbc);
    SplittableRandom r = new SplittableRandom(seed);

    Map<UUID, Order> existing = new HashMap<>();
    int[] refs = {0};
    for (int i = 0; i < initial; i++) {
      Order o = insert(jdbc, repo, r, refs);
      existing.put(o.id(), o);
    }
    String merchant = MERCHANTS.get(r.nextInt(MERCHANTS.size()));
    OrderFilter filter = randomFilter(r, refs[0]);

    Set<UUID> before = new HashSet<>();
    existing.values().stream()
        .filter(o -> o.merchantId().equals(merchant) && filter.matches(o))
        .forEach(o -> before.add(o.id()));
    Set<UUID> inserted = new HashSet<>();
    List<Order> seen = new ArrayList<>();
    Set<UUID> seenIds = new HashSet<>();

    String cursor = null;
    int pages = 0;
    while (true) {
      OrderPosition after = cursor == null ? null : cursors.decode(merchant, filter, cursor);
      OrderPage page = service.list(merchant, filter, after, pageSize);
      pages++;
      assertThat(page.orders()).hasSizeLessThanOrEqualTo(pageSize);
      if (page.hasMore()) {
        assertThat(page.orders()).as("only the last page is short").hasSize(pageSize);
      }
      for (Order o : page.orders()) {
        assertThat(seenIds.add(o.id())).as("repeated %s", o.id()).isTrue();
        assertThat(o.merchantId()).isEqualTo(merchant);
        assertThat(filter.matches(o)).as("%s matches %s", o, filter).isTrue();
        assertThat(before.contains(o.id()) || inserted.contains(o.id()))
            .as("%s existed before or was inserted while paging", o.id())
            .isTrue();
        seen.add(o);
      }
      for (int i = 0; i < insertsPerPage; i++) {
        inserted.add(insert(jdbc, repo, r, refs).id());
      }
      if (!page.hasMore()) {
        break;
      }
      cursor = cursors.encode(merchant, filter, page.next());
      assertThat(pages).as("paging terminates").isLessThan(1_000);
    }

    assertThat(seenIds).as("no gaps").containsAll(before);
    List<OrderPosition> positions = seen.stream().map(OrderPosition::of).toList();
    assertThat(positions).as("newest first, strictly").isSortedAccordingTo(NEWEST_FIRST);
    assertThat(new HashSet<>(positions)).hasSameSizeAs(positions);
    Statistics.label("pages").collect(pages == 1 ? "1" : pages <= 5 ? "2-5" : ">5");
    Statistics.label("filtered").collect(!filter.equals(OrderFilter.ALL));
  }

  private static OrderFilter randomFilter(SplittableRandom r, int refs) {
    Set<OrderState> states = EnumSet.noneOf(OrderState.class);
    if (r.nextInt(3) == 0) {
      states.add(STATES.get(r.nextInt(STATES.size())));
      if (r.nextBoolean()) {
        states.add(OrderState.FAILED);
      }
    }
    Instant from = null;
    Instant before = null;
    if (r.nextInt(3) == 0) {
      from = BASE.plusSeconds(r.nextInt(10));
    }
    if (r.nextInt(3) == 0) {
      before = (from == null ? BASE : from).plusSeconds(1 + r.nextInt(10));
    }
    String currency = r.nextInt(3) == 0 ? CURRENCIES.get(r.nextInt(CURRENCIES.size())) : null;
    String reference = null;
    if (r.nextInt(6) == 0) {
      reference = "ref-" + r.nextInt(refs + 2);
    }
    return new OrderFilter(states, from, before, currency, reference);
  }

  /**
   * One order for a random merchant, at one of twelve whole seconds plus a random microsecond in
   * one case out of three, so ties are common but not universal. Some carry a reference and some
   * are moved on to {@code RELEASING_INVENTORY}.
   */
  private static Order insert(
      JdbcClient jdbc, OrderRepository repo, SplittableRandom r, int[] refs) {
    Instant at = BASE.plusSeconds(r.nextInt(12));
    if (r.nextInt(3) == 0) {
      at = at.plusNanos(1_000L * r.nextInt(1_000_000));
    }
    String currency = CURRENCIES.get(r.nextInt(CURRENCIES.size()));
    String reference = r.nextInt(4) == 0 ? "ref-" + refs[0]++ : null;
    Order o =
        Order.create(
            UUID.randomUUID(),
            MERCHANTS.get(r.nextInt(MERCHANTS.size())),
            reference,
            Money.of(1000, currency),
            List.of(new LineItem("sku-1", 1, 1000)),
            Orders.ADDRESS,
            "pm_card_ok",
            "corr-" + UUID.randomUUID(),
            at);
    assertThat(repo.insert(o)).isTrue();
    if (r.nextInt(3) == 0) {
      jdbc.sql("UPDATE orders SET state = 'RELEASING_INVENTORY' WHERE id = :id")
          .param("id", o.id())
          .update();
      return repo.findById(o.id()).orElseThrow();
    }
    return o;
  }
}
