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
 * with full-jitter backoff until {@link WebhookProperties#retryWindow()} after the event, when the
 * last attempt is made at the edge of the window and the delivery is given up.
 *
 * <p>One order's events reach each endpoint in the order they happened: a later event waits while
 * an earlier one to the same endpoint is still pending, even across retries. Other orders, and
 * other endpoints, don't wait. Delivery is at least once; a receiver deduplicates by {@code
 * Payflow-Event-Id}.
 */
public class WebhookDispatcher implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);

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
    MDC.put("order_id", c.orderId().toString());
    try {
      Instant started = clock.instant();
      long t0 = System.nanoTime();
      Outcome outcome = null;
      String error = null;
      try {
        String secret = endpoints.signingSecret(c.merchantId(), c.endpointId());
        f.started = started;
        outcome = send(c, secret, started);
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

  private Outcome send(Claim c, String secret, Instant at) {
    byte[] body = c.payload().getBytes(StandardCharsets.UTF_8);
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put(
        WebhookSignature.HEADER,
        WebhookSignature.header(at.getEpochSecond(), body, List.of(secret)));
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

  /** Settles on a 2xx, otherwise schedules the next attempt or gives up past the window. */
  private void record(Claim c, Attempt a, Outcome outcome) {
    Instant now = clock.instant();
    boolean held;
    String settled = null;
    if (outcome instanceof Answered ans && ans.isSuccess()) {
      held = deliveries.succeed(c, a, now);
      settled = "succeeded";
    } else {
      Instant deadline = c.createdAt().plus(props.retryWindow());
      if (!now.isBefore(deadline)) {
        held = deliveries.giveUp(c, a, now);
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
  }

  private static String tag(Outcome o) {
    return switch (o) {
      case Answered a -> (a.status() / 100) + "xx";
      case Failed f -> f.failure().code();
    };
  }

  private void dispatch() {
    while (running) {
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
