package io.github.laiwf94.payflow.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.laiwf94.payflow.support.Db;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class OrderRepositoryTest {

  private static final Duration LEASE = Duration.ofSeconds(30);

  @Autowired JdbcClient jdbc;
  @Autowired OrderRepository repo;

  private final Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

  @BeforeEach
  void clean() {
    Db.truncateAll(jdbc);
  }

  @Test
  void roundTripsEveryField() {
    Order o = Orders.pending("acme", now);
    repo.insert(o);
    assertThat(repo.findById(o.id())).contains(o);
    assertThat(repo.findForMerchant("acme", o.id())).contains(o);
    assertThat(repo.findForMerchant("globex", o.id())).isEmpty();
    assertThat(repo.findById(UUID.randomUUID())).isEmpty();
  }

  @Test
  void claimsOnlyDueAndUnleasedOrders() {
    Order due = Orders.pending("acme", now);
    Order later = Orders.pending("acme", now.plusSeconds(10));
    repo.insert(due);
    repo.insert(later);

    List<Claim> first = repo.claimDue(now, LEASE, 10);
    assertThat(first).extracting(c -> c.order().id()).containsExactly(due.id());
    assertThat(first.get(0).recovered()).isFalse();
    assertThat(repo.claimDue(now, LEASE, 10)).as("lease still held").isEmpty();
    assertThat(repo.claimDue(now.plusSeconds(11), LEASE, 10))
        .as("later order is due, first lease still held")
        .extracting(c -> c.order().id())
        .containsExactly(later.id());
  }

  @Test
  void anExpiredLeaseIsTakenOverAndMarkedRecovered() {
    Order o = Orders.pending("acme", now);
    repo.insert(o);
    Claim first = repo.claimDue(now, LEASE, 1).get(0);
    Claim second = repo.claimDue(now.plus(LEASE).plusMillis(1), LEASE, 1).get(0);
    assertThat(second.recovered()).isTrue();
    assertThat(second.leaseToken()).isNotEqualTo(first.leaseToken());

    StateChange change =
        StateChange.to(OrderState.INVENTORY_RESERVED)
            .stepStartedAt(now)
            .nextAttemptAt(now)
            .reservationId("res-1")
            .build();
    assertThat(repo.update(o.id(), first.leaseToken(), OrderState.PENDING, change, now))
        .as("the old owner is fenced out")
        .isEmpty();
    assertThat(repo.update(o.id(), second.leaseToken(), OrderState.PENDING, change, now))
        .get()
        .satisfies(
            u -> {
              assertThat(u.state()).isEqualTo(OrderState.INVENTORY_RESERVED);
              assertThat(u.reservationId()).isEqualTo("res-1");
              assertThat(u.version()).isEqualTo(1);
            });
  }

  @Test
  void releasedLeasesAreNotRecoveries() {
    Order o = Orders.pending("acme", now);
    repo.insert(o);
    Claim c = repo.claimDue(now, LEASE, 1).get(0);
    assertThat(repo.release(o.id(), c.leaseToken())).isTrue();
    assertThat(repo.release(o.id(), c.leaseToken())).isFalse();
    assertThat(repo.claimDue(now, LEASE, 1).get(0).recovered()).isFalse();
  }

  @Test
  void updateIsFencedByExpectedState() {
    Order o = Orders.pending("acme", now);
    repo.insert(o);
    Claim c = repo.claimDue(now, LEASE, 1).get(0);
    StateChange change =
        StateChange.to(OrderState.PAYMENT_AUTHORIZED).stepStartedAt(now).nextAttemptAt(now).build();
    assertThat(repo.update(o.id(), c.leaseToken(), OrderState.INVENTORY_RESERVED, change, now))
        .isEmpty();
  }

  @Test
  void keepingTheLeaseExtendsItAndReleasingClearsIt() {
    Order o = Orders.pending("acme", now);
    repo.insert(o);
    Claim c = repo.claimDue(now, LEASE, 1).get(0);
    Instant until = now.plusSeconds(90);
    repo.update(
        o.id(),
        c.leaseToken(),
        OrderState.PENDING,
        StateChange.to(OrderState.INVENTORY_RESERVED)
            .stepStartedAt(now)
            .nextAttemptAt(now)
            .keepLeaseUntil(until)
            .build(),
        now);
    assertThat(repo.claimDue(now.plusSeconds(60), LEASE, 1)).isEmpty();
    Order retried =
        repo.update(
                o.id(),
                c.leaseToken(),
                OrderState.INVENTORY_RESERVED,
                StateChange.to(OrderState.INVENTORY_RESERVED)
                    .attempt(1)
                    .nextAttemptAt(now.plusSeconds(5))
                    .build(),
                now)
            .orElseThrow();
    assertThat(retried.attempt()).isEqualTo(1);
    assertThat(retried.stepStartedAt()).as("kept when not given").isEqualTo(now);
    assertThat(repo.claimDue(now.plusSeconds(5), LEASE, 1)).hasSize(1);
  }

  @Test
  void terminalChangesMustNotScheduleAndOthersMust() {
    UUID id = UUID.randomUUID();
    UUID token = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                repo.update(
                    id,
                    token,
                    OrderState.RELEASING_INVENTORY,
                    StateChange.to(OrderState.FAILED).nextAttemptAt(now).build(),
                    now))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                repo.update(
                    id,
                    token,
                    OrderState.PENDING,
                    StateChange.to(OrderState.INVENTORY_RESERVED).build(),
                    now))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void concurrentClaimersNeverShareAnOrder() throws Exception {
    for (int i = 0; i < 200; i++) {
      repo.insert(Orders.pending("acme", now));
    }
    ExecutorService pool = Executors.newFixedThreadPool(8);
    try {
      List<Callable<List<UUID>>> tasks = new ArrayList<>();
      for (int t = 0; t < 8; t++) {
        tasks.add(
            () -> {
              List<UUID> mine = new ArrayList<>();
              List<Claim> batch = repo.claimDue(now, LEASE, 7);
              while (!batch.isEmpty()) {
                batch.forEach(c -> mine.add(c.order().id()));
                batch = repo.claimDue(now, LEASE, 7);
              }
              return mine;
            });
      }
      Set<UUID> all = new HashSet<>();
      int total = 0;
      for (Future<List<UUID>> f : pool.invokeAll(tasks)) {
        List<UUID> ids = f.get();
        total += ids.size();
        all.addAll(ids);
      }
      assertThat(total).isEqualTo(200);
      assertThat(all).hasSize(200);
    } finally {
      pool.shutdownNow();
    }
  }
}
