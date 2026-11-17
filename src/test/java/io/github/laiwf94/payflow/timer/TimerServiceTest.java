package io.github.laiwf94.payflow.timer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.laiwf94.payflow.support.Db;
import io.github.laiwf94.payflow.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Scheduling, moving and cancelling timers, each in the caller's transaction and only there. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class TimerServiceTest {

  static final String KIND = "test.service";

  @Autowired JdbcClient jdbc;
  @Autowired PlatformTransactionManager txManager;
  @Autowired TimerService wired;
  @Autowired TimerWorker wiredWorker;

  private final MutableClock clock = new MutableClock(Instant.parse("2026-11-23T10:00:00Z"));
  private final AtomicInteger wakeUps = new AtomicInteger();
  private TimerSignal signal;
  private TimerRepository repo;
  private TimerService timers;
  private TransactionTemplate tx;

  @BeforeEach
  void setUp() {
    Db.truncateAll(jdbc);
    signal =
        new TimerSignal() {
          @Override
          public synchronized void wakeUp() {
            wakeUps.incrementAndGet();
            super.wakeUp();
          }
        };
    repo = new TimerRepository(jdbc);
    timers = new TimerService(repo, handlers(KIND), signal, clock, Duration.ofMillis(100));
    tx = new TransactionTemplate(txManager);
  }

  static TimerHandlers handlers(String... kinds) {
    return new TimerHandlers(
        java.util.Arrays.stream(kinds)
            .map(
                k ->
                    (TimerHandler)
                        new TimerHandler() {
                          @Override
                          public String kind() {
                            return k;
                          }

                          @Override
                          public void fire(Timer timer) {}
                        })
            .toList());
  }

  @Test
  void everyChangeNeedsTheCallersTransaction() {
    UUID subject = UUID.randomUUID();
    Instant due = clock.instant();
    assertThatThrownBy(() -> timers.schedule(KIND, subject, due))
        .isInstanceOf(IllegalTransactionStateException.class)
        .hasMessageContaining("caller's transaction");
    assertThatThrownBy(() -> timers.reschedule(KIND, subject, due))
        .isInstanceOf(IllegalTransactionStateException.class);
    assertThatThrownBy(() -> timers.cancel(KIND, subject))
        .isInstanceOf(IllegalTransactionStateException.class);
    // The application's bean is held to the same rule.
    assertThatThrownBy(() -> wired.cancel(KIND, subject))
        .isInstanceOf(IllegalTransactionStateException.class);
    assertThat(count()).isZero();
  }

  @Test
  void aScheduleExistsExactlyWhenItsTransactionCommits() {
    UUID kept = UUID.randomUUID();
    UUID rolledBack = UUID.randomUUID();
    Instant due = clock.instant().plusSeconds(90);
    tx.executeWithoutResult(s -> assertThat(timers.schedule(KIND, kept, due)).isTrue());
    tx.executeWithoutResult(
        s -> {
          timers.schedule(KIND, rolledBack, due);
          // Visible inside the transaction that made it...
          assertThat(timers.find(KIND, rolledBack)).isPresent();
          s.setRollbackOnly();
        });
    // ...and gone with it.
    assertThat(timers.find(KIND, rolledBack)).isEmpty();
    Timer t = timers.find(KIND, kept).orElseThrow();
    assertThat(t.dueAt()).isEqualTo(due);
    assertThat(t.attempt()).isZero();
    assertThat(t.lastError()).isNull();
    assertThat(t.createdAt()).isEqualTo(clock.instant());
    assertThat(nextAttemptAt(kept)).isEqualTo(due);
  }

  @Test
  void schedulingAgainKeepsTheTimerThatIsThere() {
    UUID subject = UUID.randomUUID();
    Instant first = clock.instant().plusSeconds(60);
    tx.executeWithoutResult(s -> assertThat(timers.schedule(KIND, subject, first)).isTrue());
    tx.executeWithoutResult(
        s -> assertThat(timers.schedule(KIND, subject, first.plusSeconds(60))).isFalse());
    assertThat(timers.find(KIND, subject).orElseThrow().dueAt()).isEqualTo(first);
    // Another kind for the same subject is another timer.
    TimerService two =
        new TimerService(repo, handlers(KIND, "test.other"), signal, clock, Duration.ZERO);
    tx.executeWithoutResult(s -> assertThat(two.schedule("test.other", subject, first)).isTrue());
    assertThat(count()).isEqualTo(2);
  }

  @Test
  void rescheduleMovesATimerAddsAMissingOneAndStartsItsAttemptsOver() {
    UUID subject = UUID.randomUUID();
    Instant due = clock.instant().plusSeconds(60);
    tx.executeWithoutResult(s -> timers.reschedule(KIND, subject, due));
    assertThat(timers.find(KIND, subject).orElseThrow().dueAt()).isEqualTo(due);

    // A failed fire left an attempt, an error and a lease behind.
    jdbc.sql(
            """
            UPDATE timers SET attempt = 3, last_error = 'boom', lease_token = gen_random_uuid(),
                next_attempt_at = next_attempt_at + interval '1 hour'
            """)
        .update();
    clock.advance(Duration.ofSeconds(5));
    Instant later = due.plusSeconds(600);
    tx.executeWithoutResult(s -> timers.reschedule(KIND, subject, later));
    Timer t = timers.find(KIND, subject).orElseThrow();
    assertThat(t.dueAt()).isEqualTo(later);
    assertThat(t.attempt()).isZero();
    assertThat(t.lastError()).isNull();
    assertThat(t.updatedAt()).isEqualTo(clock.instant());
    assertThat(t.createdAt()).isEqualTo(clock.instant().minusSeconds(5));
    assertThat(nextAttemptAt(subject)).isEqualTo(later);
    assertThat(leaseToken(subject)).isNull();

    // Earlier is allowed too, including the past: it fires as soon as a worker gets to it.
    Instant past = clock.instant().minusSeconds(30);
    tx.executeWithoutResult(s -> timers.reschedule(KIND, subject, past));
    assertThat(timers.find(KIND, subject).orElseThrow().dueAt()).isEqualTo(past);
    assertThat(count()).isEqualTo(1);
  }

  @Test
  void cancelRemovesTheTimerOnceAndARolledBackCancelKeepsIt() {
    UUID subject = UUID.randomUUID();
    tx.executeWithoutResult(s -> timers.schedule(KIND, subject, clock.instant()));
    tx.executeWithoutResult(
        s -> {
          assertThat(timers.cancel(KIND, subject)).isTrue();
          s.setRollbackOnly();
        });
    assertThat(timers.find(KIND, subject)).isPresent();
    tx.executeWithoutResult(s -> assertThat(timers.cancel(KIND, subject)).isTrue());
    tx.executeWithoutResult(s -> assertThat(timers.cancel(KIND, subject)).isFalse());
    assertThat(timers.find(KIND, subject)).isEmpty();
  }

  @Test
  void onlyKindsWithAHandlerHereMayBeUsed() {
    UUID subject = UUID.randomUUID();
    Instant due = clock.instant();
    tx.executeWithoutResult(
        s -> {
          assertThatThrownBy(() -> timers.schedule("test.unknown", subject, due))
              .isInstanceOf(IllegalArgumentException.class)
              .hasMessageContaining("no handler for timer kind test.unknown");
          assertThatThrownBy(() -> timers.reschedule("test.unknown", subject, due))
              .isInstanceOf(IllegalArgumentException.class);
          assertThatThrownBy(() -> timers.cancel("test.unknown", subject))
              .isInstanceOf(IllegalArgumentException.class);
          assertThatThrownBy(() -> timers.schedule(KIND, null, due))
              .isInstanceOf(NullPointerException.class);
          assertThatThrownBy(() -> timers.schedule(KIND, subject, null))
              .isInstanceOf(NullPointerException.class);
        });
    assertThat(count()).isZero();
  }

  @Test
  void handlerKindsAreCheckedWhenTheyAreCollected() {
    assertThatThrownBy(() -> handlers("Order.Expiry"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("timer kind must match");
    assertThatThrownBy(() -> handlers("a".repeat(64))).isInstanceOf(IllegalArgumentException.class);
    assertThat(handlers("a".repeat(63)).kinds()).hasSize(1);
    assertThatThrownBy(() -> handlers("order.expiry", "order.expiry"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("two handlers for timer kind order.expiry");
    assertThat(handlers().isEmpty()).isTrue();
    assertThat(TimerHandlers.of().kinds()).isEmpty();
  }

  @Test
  void aCommittedScheduleThatIsDueSoonWakesTheWorkerAndOneLaterDoesNot() {
    UUID soon = UUID.randomUUID();
    tx.executeWithoutResult(s -> timers.schedule(KIND, soon, clock.instant().plusMillis(50)));
    assertThat(wakeUps).hasValue(1);
    tx.executeWithoutResult(s -> timers.reschedule(KIND, soon, clock.instant().minusSeconds(1)));
    assertThat(wakeUps).hasValue(2);
    tx.executeWithoutResult(
        s -> timers.schedule(KIND, UUID.randomUUID(), clock.instant().plusSeconds(10)));
    // Scheduling one that exists adds nothing to wake the worker for.
    tx.executeWithoutResult(s -> timers.schedule(KIND, soon, clock.instant()));
    tx.executeWithoutResult(
        s -> {
          timers.schedule(KIND, UUID.randomUUID(), clock.instant());
          s.setRollbackOnly();
        });
    assertThat(wakeUps).hasValue(2);
  }

  @Test
  void theApplicationsWorkerHasNoHandlersYetAndStaysIdle() {
    assertThat(wiredWorker.isRunning()).isFalse();
    TimerWorker idle =
        new TimerWorker(
            repo,
            TimerHandlers.of(),
            new TimerProperties(
                true,
                Duration.ofMillis(100),
                10,
                1,
                Duration.ofSeconds(30),
                Duration.ofMillis(10),
                Duration.ofMillis(100),
                Duration.ZERO),
            signal,
            txManager,
            clock,
            new io.github.laiwf94.payflow.saga.Backoff(
                Duration.ofMillis(10), Duration.ofMillis(100), new java.util.SplittableRandom(1)),
            io.github.laiwf94.payflow.saga.CrashPoints.NONE,
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    idle.start();
    assertThat(idle.isRunning()).isFalse();
    assertThat(idle.runOnce()).isZero();
    idle.stop();
  }

  @Test
  void settingsAreChecked() {
    Duration s = Duration.ofSeconds(1);
    Duration ms = Duration.ofMillis(10);
    assertThat(new TimerProperties(true, ms, 1, 1, s, ms, ms, Duration.ZERO).batchSize()).isOne();
    List<Runnable> bad =
        List.of(
            () -> new TimerProperties(true, Duration.ZERO, 1, 1, s, ms, ms, s),
            () -> new TimerProperties(true, ms, 0, 1, s, ms, ms, s),
            () -> new TimerProperties(true, ms, 1001, 1, s, ms, ms, s),
            () -> new TimerProperties(true, ms, 1, 0, s, ms, ms, s),
            () -> new TimerProperties(true, ms, 1, 257, s, ms, ms, s),
            () -> new TimerProperties(true, ms, 1, 1, ms, ms, ms, s),
            () -> new TimerProperties(true, ms, 1, 1, s, Duration.ZERO, ms, s),
            () -> new TimerProperties(true, ms, 1, 1, s, s, ms, s),
            () -> new TimerProperties(true, ms, 1, 1, s, ms, ms, Duration.ofMinutes(6)),
            () -> new TimerProperties(true, ms, 1, 1, s, ms, ms, Duration.ofSeconds(-1)));
    bad.forEach(b -> assertThatThrownBy(b::run).isInstanceOf(IllegalArgumentException.class));
  }

  private int count() {
    return jdbc.sql("SELECT count(*) FROM timers").query(Integer.class).single();
  }

  private Instant nextAttemptAt(UUID subject) {
    return jdbc.sql("SELECT next_attempt_at FROM timers WHERE subject_id = :s")
        .param("s", subject)
        .query(java.time.OffsetDateTime.class)
        .single()
        .toInstant();
  }

  private UUID leaseToken(UUID subject) {
    return jdbc.sql("SELECT lease_token FROM timers WHERE subject_id = :s")
        .param("s", subject)
        .query((rs, n) -> rs.getObject(1, UUID.class))
        .list()
        .getFirst();
  }
}
