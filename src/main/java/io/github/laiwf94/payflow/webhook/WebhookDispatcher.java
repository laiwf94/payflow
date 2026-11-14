package io.github.laiwf94.payflow.webhook;

import io.github.laiwf94.payflow.saga.Backoff;
import io.github.laiwf94.payflow.security.SecretBox;
import io.github.laiwf94.payflow.webhook.SafeHttpSender.Answered;
import io.github.laiwf94.payflow.webhook.SafeHttpSender.Failed;
import io.github.laiwf94.payflow.webhook.SafeHttpSender.Outcome;
import io.github.laiwf94.payflow.webhook.WebhookDeliveryRepository.Attempt;
import io.github.laiwf94.payflow.webhook.WebhookDeliveryRepository.Claim;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.SmartLifecycle;

/**
 * Sends webhook deliveries, which the outbox relay creates as it publishes each event.
 *
 * <p>A dispatcher thread claims due deliveries (see {@link WebhookDeliveryRepository#claimDue}) and
 * hands each to a virtual thread, at most {@link WebhookProperties#concurrency()} at once and
 * {@link WebhookProperties#perEndpoint()} per endpoint, so an endpoint that hangs until every
 * attempt times out holds a few slots, not all of them. Each attempt signs the stored payload with
 * the endpoint's secret ({@link WebhookSignature}), posts it through the {@link SafeHttpSender} and
 * records the attempt. A 2xx settles the delivery; anything else, or no answer at all, retries it
 * with full-jitter backoff until {@link WebhookProperties#retryWindow()} after the event (or after
 * its replay), when the last attempt is made at the edge of the window and the delivery is given
 * up.
 *
 * <p>One order's events reach each endpoint in the order they happened: a later event waits while
 * an earlier one to the same endpoint is still pending, even across retries. Other orders, and
 * other endpoints, don't wait. Delivery is at least once; a receiver deduplicates by {@code
 * Payflow-Event-Id}.
 *
 * <p>A test ping ({@link WebhookEvents#PING}) belongs to no order and is tried once: whatever its
 * one attempt gets is the answer to whether the endpoint works.
 *
 * <p>Every delivery is signed with each of the endpoint's secrets that still signs: during a
 * rotation the header carries the new secret's signature and the old one's. Each answer also feeds
 * the endpoint's health (see {@link WebhookEndpointService#failed}): an endpoint that fails every
 * attempt for {@link WebhookProperties#disableAfter()} is disabled and sent a final notice, which
 * goes out once although the endpoint is now disabled. Once a minute the dispatcher also wipes the
 * secrets rotations left behind once they stop signing.
 */
public class WebhookDispatcher implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);

  /** How often expired previous secrets are wiped. */
  private static final long SWEEP_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(1);

  public static final String HEADER_EVENT_ID = "Payflow-Event-Id";
  public static final String HEADER_EVENT_TYPE = "Payflow-Event-Type";
  public static final String HEADER_DELIVERY_ID = "Payflow-Delivery-Id";
  public static final String HEADER_ATTEMPT = "Payflow-Delivery-Attempt";

  /** A claim this instance holds, and when its attempt started, once it has. */
  private static final class InFlight {
    final Claim claim;
    volatile Instant started;

    InFlight(Claim claim) {
      this.claim = claim;
    }
  }

  private final WebhookDeliveryRepository deliveries;
  private final WebhookEndpointService endpoints;
  private final SafeHttpSender sender;
  private final WebhookProperties props;
  private final Clock clock;
  private final Backoff backoff;
  private final MeterRegistry meters;
  private final Semaphore permits;
  private final ExecutorService executor =
      Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("webhook-", 0).factory());
  private final Object signal = new Object();
  private final Map<UUID, InFlight> inFlight = new ConcurrentHashMap<>();

  private boolean woken;
  private long lastSweep = System.nanoTime() - SWEEP_INTERVAL_NANOS;
  private volatile boolean running;
  private Thread dispatcher;

  public WebhookDispatcher(
      WebhookDeliveryRepository deliveries,
      WebhookEndpointService endpoints,
      SafeHttpSender sender,
      WebhookProperties props,
      Clock clock,
      Backoff backoff,
      MeterRegistry meters) {
    this.deliveries = deliveries;
    this.endpoints = endpoints;
    this.sender = sender;
    this.props = props;
    this.clock = clock;
    this.backoff = backoff;
    this.meters = meters;
    this.permits = new Semaphore(props.concurrency());
    Gauge.builder("payflow.webhooks.in_flight", inFlight, Map::size)
        .description("webhook attempts this instance is making")
        .register(meters);
  }

  /** Wakes the dispatcher, e.g. when the relay has just created deliveries. */
  public void wakeUp() {
    synchronized (signal) {
      woken = true;
      signal.notifyAll();
    }
  }

  /**
   * Claims one batch and sends it on the calling thread, one delivery after another. Returns the
   * number of attempts made.
   */
  public int runOnce() {
    List<Claim> claims = claim(props.batchSize());
    for (Claim c : claims) {
      InFlight f = new InFlight(c);
      inFlight.put(c.id(), f);
      attempt(f);
    }
    return claims.size();
  }

  /**
   * Claims up to {@code free} deliveries, skipping endpoints at their per-endpoint limit, and gives
   * back what a batch brings past that limit.
   */
  private List<Claim> claim(int free) {
    Map<UUID, Integer> busy = new HashMap<>();
    for (InFlight f : inFlight.values()) {
      busy.merge(f.claim.endpointId(), 1, Integer::sum);
    }
    Set<UUID> full =
        busy.entrySet().stream()
            .filter(e -> e.getValue() >= props.perEndpoint())
            .map(Map.Entry::getKey)
            .collect(Collectors.toSet());
    Instant now = clock.instant();
    List<Claim> claimed = deliveries.claimDue(now, props.lease(), free, full);
    List<Claim> kept = new ArrayList<>(claimed.size());
    for (Claim c : claimed) {
      if (busy.merge(c.endpointId(), 1, Integer::sum) > props.perEndpoint()) {
        deliveries.unclaim(c, now);
      } else {
        kept.add(c);
      }
    }
    return kept;
  }

  private void attempt(InFlight f) {
    Claim c = f.claim;
    MDC.put("delivery_id", c.id().toString());
    MDC.put("event_id", c.eventId().toString());
    if (c.orderId() != null) {
      MDC.put("order_id", c.orderId().toString());
    }
    try {
      Instant started = clock.instant();
      long t0 = System.nanoTime();
      Outcome outcome = null;
      String error = null;
      try {
        List<String> secrets = endpoints.signingSecrets(c.merchantId(), c.endpointId(), started);
        f.started = started;
        outcome = send(c, secrets, started);
      } catch (WebhookEndpointService.NotFound e) {
        // Deleted since the claim: the delete gave its pending deliveries up already.
        return;
      } catch (SecretBox.CannotOpen e) {
        // The key that sealed the secret isn't configured here. Retrying lets an instance that
        // has it, or this one after a fix, send the delivery; the attempt says why.
        error = "secret: " + e.getMessage();
      } catch (RuntimeException e) {
        log.error("webhook attempt failed before an answer", e);
        error = "internal: " + e.getClass().getSimpleName();
      }
      long nanos = System.nanoTime() - t0;
      // A stop that interrupts a send must not also break the write that records it.
      boolean interrupted = Thread.interrupted();
      if (!inFlight.remove(c.id(), f)) {
        return; // handed back at shutdown, which recorded the attempt
      }
      Timer.builder("payflow.webhooks.attempt")
          .tag("outcome", outcome == null ? "internal" : tag(outcome))
          .register(meters)
          .record(nanos, TimeUnit.NANOSECONDS);
      Attempt a =
          outcome == null
              ? new Attempt(started, (int) (nanos / 1_000_000), null, null, error)
              : attempt(started, outcome);
      record(c, a, outcome);
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    } finally {
      inFlight.remove(c.id(), f);
      MDC.remove("delivery_id");
      MDC.remove("event_id");
      MDC.remove("order_id");
    }
  }

  private Outcome send(Claim c, List<String> secrets, Instant at) {
    byte[] body = c.payload().getBytes(StandardCharsets.UTF_8);
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put(
        WebhookSignature.HEADER, WebhookSignature.header(at.getEpochSecond(), body, secrets));
    headers.put(HEADER_EVENT_ID, c.eventId().toString());
    headers.put(HEADER_EVENT_TYPE, c.eventType());
    headers.put(HEADER_DELIVERY_ID, c.id().toString());
    headers.put(HEADER_ATTEMPT, Integer.toString(c.attempt()));
    return sender.post(c.url(), headers, body);
  }

  private static Attempt attempt(Instant started, Outcome o) {
    int ms = (int) Math.min(Integer.MAX_VALUE, o.latency().toMillis());
    return switch (o) {
      case Answered a -> new Attempt(started, ms, a.status(), a.body(), null);
      case Failed f -> new Attempt(started, ms, null, null, f.describe());
    };
  }

  /**
   * Settles on a 2xx, otherwise schedules the next attempt or gives up past the window; then tells
   * the endpoint's health what the endpoint answered, when it answered anything.
   */
  private void record(Claim c, Attempt a, Outcome outcome) {
    Instant now = clock.instant();
    boolean held;
    boolean success = outcome instanceof Answered ans && ans.isSuccess();
    boolean windowSpent = false;
    String settled = null;
    if (success) {
      held = deliveries.succeed(c, a, now);
      settled = "succeeded";
    } else {
      Instant deadline = c.windowStart().plus(props.retryWindow());
      if (c.once()) {
        // A ping asks whether the endpoint works now; its one answer is the answer. A disabled
        // endpoint's final notice is a courtesy, not something to keep at for days.
        held = deliveries.giveUp(c, a, now);
        settled = "failed";
      } else if (!now.isBefore(deadline)) {
        held = deliveries.giveUp(c, a, now);
        windowSpent = true;
        settled = "failed";
        if (held) {
          log.warn(
              "webhook {} gave up after {} attempts over {}: {}",
              c.eventType(),
              c.attempt(),
              props.retryWindow(),
              a.error() != null ? a.error() : "HTTP " + a.status());
        }
      } else {
        Instant next = now.plus(backoff.delay(c.attempt() - 1));
        // The last try lands on the edge of the window rather than past it.
        held = deliveries.retry(c, a, next.isAfter(deadline) ? deadline : next, now);
      }
    }
    if (!held) {
      log.warn("lost the claim on a webhook delivery; another instance has it");
      return;
    }
    if (settled != null) {
      meters.counter("payflow.webhooks.deliveries", "state", settled).increment();
    }
    // Only what the endpoint did counts: an attempt that never got as far as sending (a secret
    // that can't be opened, a fault of payflow's) says nothing about it, and neither does the
    // notice to an endpoint that is already disabled.
    if (outcome != null && !(c.once() && WebhookEvents.ENDPOINT_DISABLED.equals(c.eventType()))) {
      health(c, a, success, windowSpent);
    }
  }

  private void health(Claim c, Attempt a, boolean success, boolean windowSpent) {
    try {
      if (success) {
        endpoints.succeeded(c.endpointId());
        return;
      }
      // A delivery that failed its whole window counts, with slack for a late first attempt,
      // when that window is at least as long as an endpoint may fail.
      boolean spentCounts = windowSpent && props.retryWindow().compareTo(props.disableAfter()) >= 0;
      endpoints
          .failed(c, a.attemptedAt(), spentCounts, props.disableAfter(), clock.instant())
          .ifPresent(
              d -> {
                meters.counter("payflow.webhooks.endpoints_disabled").increment();
                log.atWarn()
                    .addKeyValue("endpoint_id", d.endpoint().id())
                    .addKeyValue("merchant_id", d.endpoint().merchantId())
                    .log(
                        "disabled webhook endpoint {}: every attempt has failed since {}; sent"
                            + " {} final notice(s)",
                        d.endpoint().id(),
                        d.endpoint().failingSince(),
                        d.notices());
                wakeUp();
              });
    } catch (RuntimeException e) {
      // Health is bookkeeping; the attempt itself is already recorded.
      log.error("could not record webhook endpoint health", e);
    }
  }

  /** Wipes expired previous secrets, at most once per {@link #SWEEP_INTERVAL_NANOS}. */
  private void sweep() {
    long now = System.nanoTime();
    if (now - lastSweep < SWEEP_INTERVAL_NANOS) {
      return;
    }
    lastSweep = now;
    try {
      int wiped = endpoints.wipeExpiredSecrets();
      if (wiped > 0) {
        log.info("wiped {} webhook signing secret(s) that rotations left behind", wiped);
      }
    } catch (RuntimeException e) {
      log.error("could not wipe expired webhook secrets", e);
    }
  }

  private static String tag(Outcome o) {
    return switch (o) {
      case Answered a -> (a.status() / 100) + "xx";
      case Failed f -> f.failure().code();
    };
  }

  private void dispatch() {
    while (running) {
      sweep();
      try {
        int free = Math.min(permits.availablePermits(), props.batchSize());
        List<Claim> claims = free == 0 ? List.of() : claim(free);
        claims.forEach(this::submit);
        if (claims.size() < free || free == 0) {
          awaitSignal();
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (RuntimeException e) {
        // Usually the database is unreachable. Deliveries wait in Postgres.
        log.error("webhook dispatcher poll failed", e);
        try {
          awaitSignal();
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    }
  }

  private void submit(Claim c) {
    // Never blocks: the dispatcher is the only acquirer and claimed no more than were free.
    permits.acquireUninterruptibly();
    InFlight f = new InFlight(c);
    inFlight.put(c.id(), f);
    try {
      executor.execute(
          () -> {
            try {
              attempt(f);
            } catch (RuntimeException e) {
              log.error("webhook attempt failed", e);
            } finally {
              permits.release();
            }
          });
    } catch (RejectedExecutionException e) {
      permits.release();
      inFlight.remove(c.id(), f);
      deliveries.unclaim(c, clock.instant());
    }
  }

  private void awaitSignal() throws InterruptedException {
    long deadline = System.nanoTime() + props.pollInterval().toNanos();
    synchronized (signal) {
      long left;
      while (!woken && running && (left = deadline - System.nanoTime()) > 0) {
        TimeUnit.NANOSECONDS.timedWait(signal, left);
      }
      woken = false;
    }
  }

  @Override
  public void start() {
    if (!props.enabled() || running) {
      return;
    }
    running = true;
    dispatcher = Thread.ofPlatform().daemon().name("webhook-dispatcher").start(this::dispatch);
  }

  /**
   * Stops claiming and lets attempts in flight finish for up to {@link
   * WebhookProperties#shutdownGrace()}. An attempt still waiting for its answer then is recorded as
   * cut short by the stop and made due again at once, so the next instance sends it on its first
   * poll; the merchant may get that event twice, which at-least-once delivery allows.
   */
  @Override
  public void stop() {
    long started = System.nanoTime();
    long deadline = started + props.shutdownGrace().toNanos();
    running = false;
    wakeUp();
    int handedBack = 0;
    try {
      if (dispatcher != null) {
        dispatcher.join(Math.max(1, remainingMillis(deadline)));
        if (dispatcher.isAlive()) {
          dispatcher.interrupt();
          dispatcher.join(1_000);
        }
      }
      executor.shutdown();
      if (!executor.awaitTermination(
          Math.max(0, remainingMillis(deadline)), TimeUnit.MILLISECONDS)) {
        for (InFlight f : List.copyOf(inFlight.values())) {
          if (handBack(f)) {
            handedBack++;
          }
        }
        executor.shutdownNow();
        if (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
          log.warn("webhook attempts still running after they were handed back");
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    log.info(
        "webhook dispatcher stopped in {} ms; attempts handed back: {}",
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
        handedBack);
  }

  private boolean handBack(InFlight f) {
    if (!inFlight.remove(f.claim.id(), f)) {
      return false; // it finished meanwhile
    }
    Instant now = clock.instant();
    Instant began = f.started;
    try {
      boolean done =
          began == null
              ? deliveries.unclaim(f.claim, now)
              : deliveries.retry(
                  f.claim,
                  new Attempt(
                      began,
                      (int) Math.max(0, now.toEpochMilli() - began.toEpochMilli()),
                      null,
                      null,
                      "shutdown: payflow stopped before the answer arrived"),
                  now,
                  now);
      if (done) {
        meters.counter("payflow.webhooks.handed_back").increment();
      }
      return done;
    } catch (RuntimeException e) {
      log.error("could not hand back webhook delivery {} at shutdown", f.claim.id(), e);
      return false;
    }
  }

  /** Stops on its own thread, so its grace runs alongside the saga worker's, not after it. */
  @Override
  public void stop(Runnable callback) {
    Thread.ofPlatform()
        .name("webhook-dispatcher-stop")
        .start(
            () -> {
              try {
                stop();
              } finally {
                callback.run();
              }
            });
  }

  private static long remainingMillis(long deadlineNanos) {
    return TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  /** With the workers: after the web server and its in-process simulators. */
  @Override
  public int getPhase() {
    return Integer.MAX_VALUE;
  }
}
