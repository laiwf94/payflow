package io.github.laiwf94.payflow.timer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.github.laiwf94.payflow.saga.Backoff;
import io.github.laiwf94.payflow.saga.CrashPoints;
import io.github.laiwf94.payflow.support.Db;
import io.github.laiwf94.payflow.support.MutableClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The timer worker against Postgres: timers fire at or after their due time and exactly once,
 * whatever fails, races or restarts around them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class TimerWorkerTest {

  static final String KIND = "test.fire";
  static final String CHAIN = "test.chain";

  @Autowired JdbcClient jdbc;
  @Autowired PlatformTransactionManager txManager;

  private final MutableClock clock = new MutableClock(Instant.parse("2026-11-23T10:00:00Z"));
  private final List<TimerWorker> started = new ArrayList<>();
  private final List<CountDownLatch> releases = new ArrayList<>();
  private Recorder fire;
  private Recorder chain;
  private TimerHandlers handlers;
  private TimerRepository repo;
  private TimerSignal signal;
  private TimerService service;
  private TransactionTemplate tx;
  private SimpleMeterRegistry meters;

  @BeforeEach
  void setUp() {
    Db.truncateAll(jdbc);
    fire = new Recorder(KIND);
    chain = new Recorder(CHAIN);
    handlers = TimerHandlers.of(fire, chain);
    repo = new TimerRepository(jdbc);
    signal = new TimerSignal();
    service = new TimerService(repo, handlers, signal, clock, Duration.ofMillis(100));
    tx = new TransactionTemplate(txManager);
    meters = new SimpleMeterRegistry();
  }

  @AfterEach
  void stopStarted() {
    // A test that failed half way must not leave a handler holding a transaction open.
    releases.forEach(CountDownLatch::countDown);
    started.forEach(TimerWorker::stop);
  }

  /** A latch a handler waits on, opened after the test whatever happened. */
  private CountDownLatch releaseLatch() {
    CountDownLatch l = new CountDownLatch(1);
    releases.add(l);
    return l;
  }

  /** A handler that counts its calls, and the fires that committed, per subject. */
  static final class Recorder implements TimerHandler {
    final String kind;
    final AtomicInteger calls = new AtomicInteger();
    final Map<UUID, AtomicInteger> committed = new ConcurrentHashMap<>();
    final Map<UUID, Instant> firedAt = new ConcurrentHashMap<>();
    volatile Consumer<Timer> behaviour = t -> {};
    volatile Clock clock = Clock.systemUTC();

    Recorder(String kind) {
      this.kind = kind;
    }

    @Override
    public String kind() {
      return kind;
    }

    @Override
    public void fire(Timer timer) {
      calls.incrementAndGet();
      Instant at = clock.instant();
      behaviour.accept(timer);
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              committed
                  .computeIfAbsent(timer.subjectId(), k -> new AtomicInteger())
                  .incrementAndGet();
              firedAt.put(timer.subjectId(), at);
            }
          });
    }

    int committedTotal() {
      return committed.values().stream().mapToInt(AtomicInteger::get).sum();
    }
  }

  static final class Crash extends Error {
    private static final long serialVersionUID = 1L;

    Crash(String point) {
      super("crash at " + point, null, false, false);
    }
  }

  private static TimerProperties props(
      int batch, int concurrency, Duration lease, Duration poll, Duration grace) {
    return new TimerProperties(
        true,
        poll,
        batch,
        concurrency,
        lease,
        Duration.ofMillis(10),
        Duration.ofMillis(100),
        grace);
  }

  private TimerWorker worker(CrashPoints crash) {
    return worker(
        props(64, 1, Duration.ofSeconds(30), Duration.ofMillis(50), Duration.ZERO), crash, clock);
  }

  private TimerWorker worker(TimerProperties p, CrashPoints crash, Clock workerClock) {
    return new TimerWorker(
        repo,
        handlers,
        p,
        signal,
        txManager,
        workerClock,
        new Backoff(p.retryBase(), p.retryCap(), new SplittableRandom(7)),
        crash,
        meters);
  }

  private TimerWorker startedWorker(TimerProperties p, CrashPoints crash, Clock workerClock) {
    TimerWorker w = worker(p, crash, workerClock);
    started.add(w);
    w.start();
    return w;
  }

  private void schedule(String kind, UUID subject, Instant due) {
    tx.executeWithoutResult(s -> service.schedule(kind, subject, due));
  }

  @Test
  void aTimerFiresOnceAtItsDueTimeAndNotBefore() {
    TimerWorker w = worker(CrashPoints.NONE);
    UUID subject = UUID.randomUUID();
    Instant due = clock.instant().plusSeconds(10);
    schedule(KIND, subject, due);

    assertThat(w.runOnce()).isZero();
    clock.advance(Duration.ofSeconds(10).minusNanos(1_000));
    assertThat(w.runOnce()).isZero();
    clock.advance(Duration.ofNanos(1_000));
    assertThat(w.runOnce()).isOne();

    assertThat(fire.committed.get(subject)).hasValue(1);
    assertThat(service.find(KIND, subject)).isEmpty();
    clock.advance(Duration.ofHours(1));
    assertThat(w.runOnce()).isZero();
    assertThat(fire.calls).hasValue(1);
    assertThat(meters.get("payflow.timers.lateness").tag("kind", KIND).timer().count()).isOne();
    assertThat(
            meters
                .get("payflow.timers.fire")
                .tags("kind", KIND, "outcome", "fired")
                .timer()
                .count())
        .isOne();
  }

  @Test
  void theHandlerSeesTheTimerItFires() {
    TimerWorker w = worker(CrashPoints.NONE);
    UUID subject = UUID.randomUUID();
    Instant due = clock.instant().minusSeconds(5);
    schedule(KIND, subject, due);
    List<Timer> seen = new ArrayList<>();
    fire.behaviour = seen::add;
    w.runOnce();
    assertThat(seen)
        .singleElement()
        .satisfies(
            t -> {
              assertThat(t.kind()).isEqualTo(KIND);
              assertThat(t.subjectId()).isEqualTo(subject);
              assertThat(t.dueAt()).isEqualTo(due);
              assertThat(t.attempt()).isZero();
            });
  }

  @Test
  void aFailingHandlerRollsItsWritesBackAndIsRetriedWithBackoffUntilItSucceeds() {
    TimerWorker w = worker(CrashPoints.NONE);
    UUID subject = UUID.randomUUID();
    schedule(KIND, subject, clock.instant());
    AtomicInteger failures = new AtomicInteger(2);
    fire.behaviour =
        t -> {
          // A write the handler makes: it must commit with the fire or not at all.
          service.schedule(CHAIN, t.subjectId(), clock.instant());
          if (failures.getAndDecrement() > 0) {
            throw new IllegalStateException("not yet");
          }
        };

    assertThat(w.runOnce()).isOne();
    Timer after = service.find(KIND, subject).orElseThrow();
    assertThat(after.attempt()).isOne();
    assertThat(after.lastError()).isEqualTo("IllegalStateException: not yet");
    assertThat(service.find(CHAIN, subject)).isEmpty();
    assertThat(leaseToken(subject, KIND)).isNull();
    // Full jitter on attempt 0: within [now, now + 10 ms].
    assertThat(nextAttemptAt(subject, KIND))
        .isBetween(clock.instant(), clock.instant().plusMillis(10));

    clock.advance(Duration.ofSeconds(1));
    assertThat(w.runOnce()).isOne();
    assertThat(service.find(KIND, subject).orElseThrow().attempt()).isEqualTo(2);
    assertThat(nextAttemptAt(subject, KIND))
        .isBetween(clock.instant(), clock.instant().plusMillis(20));

    clock.advance(Duration.ofSeconds(1));
    assertThat(w.runOnce()).isOne();
    assertThat(service.find(KIND, subject)).isEmpty();
    assertThat(service.find(CHAIN, subject)).isPresent();
    assertThat(fire.calls).hasValue(3);
    assertThat(fire.committed.get(subject)).hasValue(1);
    assertThat(meters.get("payflow.timers.retries").tag("kind", KIND).counter().count())
        .isEqualTo(2);
  }

  @Test
  void retriesThatGoOnAreLoggedAsErrorsAndNeverGiveUp() {
    TimerWorker w = worker(CrashPoints.NONE);
    UUID subject = UUID.randomUUID();
    schedule(KIND, subject, clock.instant());
    fire.behaviour =
        t -> {
          throw new IllegalStateException("x".repeat(600));
        };
    for (int i = 0; i < TimerWorker.ALERT_ATTEMPTS + 2; i++) {
      assertThat(w.runOnce()).isOne();
      clock.advance(Duration.ofSeconds(1));
    }
    Timer t = service.find(KIND, subject).orElseThrow();
    assertThat(t.attempt()).isEqualTo(TimerWorker.ALERT_ATTEMPTS + 2);
    // The error is cut to what the column keeps.
    assertThat(t.lastError()).hasSize(TimerRepository.MAX_ERROR);
    // The ceiling stops at the cap.
    assertThat(nextAttemptAt(subject, KIND)).isBefore(clock.instant().plusMillis(101));
  }

  @Test
  void aHandlerMayScheduleItsOwnKindForTheSameSubjectAgain() {
    TimerWorker w = worker(CrashPoints.NONE);
    UUID subject = UUID.randomUUID();
    schedule(KIND, subject, clock.instant());
    fire.behaviour = t -> service.reschedule(KIND, t.subjectId(), t.dueAt().plusSeconds(5));
    Instant first = clock.instant();
    for (int i = 0; i < 3; i++) {
      assertThat(w.runOnce()).isOne();
      assertThat(w.runOnce()).isZero();
      clock.advance(Duration.ofSeconds(5));
    }
    assertThat(fire.committed.get(subject)).hasValue(3);
    assertThat(service.find(KIND, subject).orElseThrow().dueAt()).isEqualTo(first.plusSeconds(15));
  }

  @Test
  void aRescheduleOrCancelAfterTheClaimFencesTheFireOut() {
    TimerWorker w = worker(CrashPoints.NONE);
    UUID moved = UUID.randomUUID();
    UUID cancelled = UUID.randomUUID();
    schedule(KIND, moved, clock.instant());
    schedule(KIND, cancelled, clock.instant());
    List<TimerClaim> claims =
        repo.claimDue(handlers.kinds(), clock.instant(), Duration.ofSeconds(30), 10);
    assertThat(claims).hasSize(2);
    Instant later = clock.instant().plusSeconds(60);
    tx.executeWithoutResult(
        s -> {
          service.reschedule(KIND, moved, later);
          assertThat(service.cancel(KIND, cancelled)).isTrue();
        });

    claims.forEach(c -> assertThat(w.process(c)).isEqualTo(TimerWorker.Result.LOST));
    assertThat(fire.calls).hasValue(0);
    assertThat(service.find(KIND, moved).orElseThrow().dueAt()).isEqualTo(later);
    assertThat(leaseToken(moved, KIND)).isNull();
    assertThat(service.find(KIND, cancelled)).isEmpty();
    assertThat(meters.get("payflow.timers.lost").tag("kind", KIND).counter().count()).isEqualTo(2);

    clock.advance(Duration.ofSeconds(60));
    assertThat(w.runOnce()).isOne();
    assertThat(fire.committed.get(moved)).hasValue(1);
    assertThat(fire.committed).doesNotContainKey(cancelled);
  }

  @Test
  void aCancelWaitsForAFireThatIsCommittingAndThenFindsNothing() throws Exception {
    TimerWorker w = worker(CrashPoints.NONE);
    UUID subject = UUID.randomUUID();
    schedule(KIND, subject, clock.instant());
    CountDownLatch inHandler = new CountDownLatch(1);
    CountDownLatch release = releaseLatch();
    fire.behaviour =
        t -> {
          inHandler.countDown();
          awaitUninterruptibly(release);
        };
    Thread firing = Thread.ofVirtual().start(w::runOnce);
    assertThat(inHandler.await(10, TimeUnit.SECONDS)).isTrue();
    AtomicInteger cancelled = new AtomicInteger(-1);
    Thread cancelling =
        Thread.ofVirtual()
            .start(
                () ->
                    tx.executeWithoutResult(
                        s -> cancelled.set(service.cancel(KIND, subject) ? 1 : 0)));
    // The cancel is blocked on the row the fire deleted.
    cancelling.join(300);
    assertThat(cancelling.isAlive()).isTrue();
    release.countDown();
    firing.join(10_000);
    cancelling.join(10_000);
    assertThat(cancelled).hasValue(0);
    assertThat(fire.committed.get(subject)).hasValue(1);
  }

  @Test
  void aCrashMidFireRollsBackAndTheTimerFiresOnceWhenTheLeaseRunsOut() {
    AtomicInteger crashes = new AtomicInteger(1);
    TimerWorker dying =
        worker(
            point -> {
              if (point.equals("after:TIMER") && crashes.getAndDecrement() > 0) {
                throw new Crash(point);
              }
            });
    UUID subject = UUID.randomUUID();
    schedule(KIND, subject, clock.instant());
    fire.behaviour = t -> service.schedule(CHAIN, t.subjectId(), clock.instant());

    assertThatThrownBy(dying::runOnce).isInstanceOf(Crash.class);
    assertThat(fire.calls).hasValue(1);
    assertThat(fire.committed).isEmpty();
    assertThat(service.find(CHAIN, subject)).isEmpty();
    // Still there, still leased by the dead worker.
    assertThat(service.find(KIND, subject)).isPresent();
    assertThat(leaseToken(subject, KIND)).isNotNull();

    TimerWorker next = worker(CrashPoints.NONE);
    clock.advance(Duration.ofSeconds(30).minusNanos(1_000));
    assertThat(next.runOnce()).isZero();
    clock.advance(Duration.ofNanos(1_000));
    List<TimerClaim> claims =
        repo.claimDue(handlers.kinds(), clock.instant(), Duration.ofSeconds(30), 10);
    assertThat(claims).singleElement().satisfies(c -> assertThat(c.recovered()).isTrue());
    assertThat(next.process(claims.getFirst())).isEqualTo(TimerWorker.Result.FIRED);
    assertThat(fire.committed.get(subject)).hasValue(1);
    assertThat(service.find(CHAIN, subject)).isPresent();
    assertThat(service.find(KIND, subject)).isEmpty();
  }

  @Test
  void aCrashRightAfterTheCommitHasStillFiredOnce() {
    TimerWorker dying =
        worker(
            point -> {
              if (point.equals("committed:TIMER")) {
                throw new Crash(point);
              }
            });
    UUID subject = UUID.randomUUID();
    schedule(KIND, subject, clock.instant());
    assertThatThrownBy(dying::runOnce).isInstanceOf(Crash.class);
    assertThat(fire.committed.get(subject)).hasValue(1);
    clock.advance(Duration.ofMinutes(5));
    assertThat(worker(CrashPoints.NONE).runOnce()).isZero();
    assertThat(fire.calls).hasValue(1);
  }

  @Test
  void anAbandonedTimerIsFencedAndClaimedAtOnceAsRecovered() {
    TimerWorker w = worker(CrashPoints.NONE);
    UUID subject = UUID.randomUUID();
    schedule(KIND, subject, clock.instant());
    TimerClaim claim =
        repo.claimDue(handlers.kinds(), clock.instant(), Duration.ofSeconds(30), 10).getFirst();
    assertThat(repo.abandon(KIND, subject, UUID.randomUUID())).isFalse();
    assertThat(repo.abandon(KIND, subject, claim.leaseToken())).isTrue();
    // The abandoned claim's late fire is fenced out...
    assertThat(w.process(claim)).isEqualTo(TimerWorker.Result.LOST);
    assertThat(fire.calls).hasValue(0);
    // ...and the next claim, at the same instant, is told it is a recovery.
    List<TimerClaim> again =
        repo.claimDue(handlers.kinds(), clock.instant(), Duration.ofSeconds(30), 10);
    assertThat(again).singleElement().satisfies(c -> assertThat(c.recovered()).isTrue());
    assertThat(w.process(again.getFirst())).isEqualTo(TimerWorker.Result.FIRED);
    assertThat(fire.committed.get(subject)).hasValue(1);
  }

  @Test
  void timersOfKindsWithoutAHandlerHereAreLeftForAnInstanceThatHasOne() {
    TimerWorker w = worker(CrashPoints.NONE);
    UUID elsewhere = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO timers (kind, subject_id, due_at, next_attempt_at, created_at, updated_at)
            VALUES ('test.newer_version', :s, :t, :t, :t, :t)
            """)
        .param("s", elsewhere)
        .param("t", clock.instant().minusSeconds(60).atOffset(java.time.ZoneOffset.UTC))
        .update();
    UUID mine = UUID.randomUUID();
    schedule(KIND, mine, clock.instant());
    assertThat(w.runOnce()).isOne();
    assertThat(fire.committed).containsOnlyKeys(mine);
    assertThat(leaseToken(elsewhere, "test.newer_version")).isNull();
    assertThat(repo.claimDue(List.of(), clock.instant(), Duration.ofSeconds(30), 10)).isEmpty();
  }

  @Test
  void stoppingGivesBackWhatHasNotStartedAndLeavesAFireInFlightToFinish() throws Exception {
    TimerWorker w =
        startedWorker(
            props(10, 1, Duration.ofSeconds(30), Duration.ofMillis(20), Duration.ofMillis(200)),
            CrashPoints.NONE,
            clock);
    UUID slow = UUID.randomUUID();
    CountDownLatch inHandler = new CountDownLatch(1);
    CountDownLatch release = releaseLatch();
    fire.behaviour =
        t -> {
          if (t.subjectId().equals(slow)) {
            inHandler.countDown();
            // Ignores the interrupt a stop sends, like a handler stuck on a lock.
            awaitUninterruptibly(release);
          }
        };
    schedule(KIND, slow, clock.instant().minusSeconds(3));
    assertThat(inHandler.await(10, TimeUnit.SECONDS)).isTrue();
    List<UUID> queued = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    queued.forEach(s -> schedule(KIND, s, clock.instant().minusSeconds(1)));
    // The dispatcher claims them; they wait behind the one permit.
    await()
        .atMost(Duration.ofSeconds(10))
        .until(() -> queued.stream().allMatch(s -> leaseToken(s, KIND) != null));

    long t0 = System.nanoTime();
    w.stop();
    assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(5));
    assertThat(w.isRunning()).isFalse();
    for (UUID s : queued) {
      assertThat(leaseToken(s, KIND)).isNull();
      assertThat(nextAttemptAt(s, KIND)).isBeforeOrEqualTo(clock.instant());
    }
    assertThat(fire.committed).isEmpty();
    assertThat(meters.get("payflow.timers.handed_back").tag("how", "unstarted").counter().count())
        .isEqualTo(3);

    // The fire in flight holds its row, so it was not abandoned under it; it commits once.
    release.countDown();
    await().atMost(Duration.ofSeconds(10)).until(() -> fire.committed.containsKey(slow));
    assertThat(service.find(KIND, slow)).isEmpty();

    // The next instance picks up the rest on its first poll.
    assertThat(worker(CrashPoints.NONE).runOnce()).isEqualTo(3);
    assertThat(fire.committedTotal()).isEqualTo(4);
  }

  /**
   * Four instances on the real clock fire 3,000 timers due within one second. One is stopped and
   * replaced halfway, and one dies in the middle of a few fires, leaving those timers leased until
   * their lease runs out. Every timer fires exactly once, and none before it is due.
   */
  @Test
  void severalInstancesFireEveryTimerOnceAndNoneEarlyThroughCrashesAndARestart() throws Exception {
    Clock real = Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));
    fire.clock = real;
    TimerService realService = new TimerService(repo, handlers, signal, real, Duration.ZERO);
    int n = 3_000;
    Instant start = real.instant().plusSeconds(3);
    SplittableRandom random = new SplittableRandom(42);
    Map<UUID, Instant> due = new ConcurrentHashMap<>();
    for (int batch = 0; batch < n / 500; batch++) {
      tx.executeWithoutResult(
          s -> {
            for (int i = 0; i < 500; i++) {
              UUID subject = UUID.randomUUID();
              Instant at = start.plusNanos(random.nextLong(1_000_000) * 1_000);
              due.put(subject, at);
              realService.schedule(KIND, subject, at);
            }
          });
    }
    assertThat(real.instant()).isBefore(start);

    AtomicInteger crashesLeft = new AtomicInteger(15);
    CrashPoints sometimes =
        point -> {
          if (point.equals("after:TIMER")
              && ThreadLocalRandomHolder.next() < 0.02
              && crashesLeft.getAndDecrement() > 0) {
            throw new Crash(point);
          }
        };
    TimerProperties p =
        props(64, 1, Duration.ofSeconds(2), Duration.ofMillis(20), Duration.ofSeconds(2));
    TimerWorker first = startedWorker(p, CrashPoints.NONE, real);
    startedWorker(p, sometimes, real);
    startedWorker(p, CrashPoints.NONE, real);
    startedWorker(p, CrashPoints.NONE, real);

    await()
        .dontCatchUncaughtExceptions()
        .atMost(Duration.ofSeconds(30))
        .until(() -> fire.committedTotal() >= n / 2);
    first.stop();
    startedWorker(p, CrashPoints.NONE, real);

    // The crashes are meant to escape the worker's threads, as they would kill a process.
    await()
        .dontCatchUncaughtExceptions()
        .atMost(Duration.ofSeconds(60))
        .until(() -> fire.committedTotal() >= n);
    // Give a double fire the chance to show itself.
    Thread.sleep(300);

    assertThat(fire.committed).hasSize(n);
    assertThat(fire.committed.values()).allSatisfy(c -> assertThat(c).hasValue(1));
    for (Map.Entry<UUID, Instant> e : due.entrySet()) {
      assertThat(fire.firedAt.get(e.getKey())).isAfterOrEqualTo(e.getValue());
    }
    assertThat(jdbc.sql("SELECT count(*) FROM timers").query(Integer.class).single()).isZero();
    assertThat(crashesLeft.get()).as("some fires crashed").isLessThan(15);
    // Each crashed fire ran its handler and rolled back; nothing else ran it twice.
    assertThat(fire.calls.get()).isEqualTo(n + (15 - Math.max(crashesLeft.get(), 0)));
  }

  /** A shared random for crash decisions on many threads. */
  private static final class ThreadLocalRandomHolder {
    static double next() {
      return java.util.concurrent.ThreadLocalRandom.current().nextDouble();
    }
  }

  /** Waits for {@code latch}, swallowing interrupts, as a handler stuck on something might. */
  private static void awaitUninterruptibly(CountDownLatch latch) {
    while (true) {
      try {
        if (latch.await(10, TimeUnit.SECONDS)) {
          return;
        }
      } catch (InterruptedException e) {
        // Deliberately ignored: the point is a handler that doesn't stop when asked.
      }
    }
  }

  private Instant nextAttemptAt(UUID subject, String kind) {
    return jdbc.sql("SELECT next_attempt_at FROM timers WHERE kind = :k AND subject_id = :s")
        .param("k", kind)
        .param("s", subject)
        .query(OffsetDateTime.class)
        .single()
        .toInstant();
  }

  private UUID leaseToken(UUID subject, String kind) {
    return jdbc.sql("SELECT lease_token FROM timers WHERE kind = :k AND subject_id = :s")
        .param("k", kind)
        .param("s", subject)
        .query((rs, n) -> rs.getObject(1, UUID.class))
        .list()
        .getFirst();
  }
}
