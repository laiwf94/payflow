package io.github.laiwf94.payflow.sim;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * A card payment provider in the shape of the common two-step APIs: authorize holds the money,
 * capture takes it, void drops the hold, refund returns captured money. Every mutating call accepts
 * an idempotency key; a retry with the same key and parameters returns the first result.
 *
 * <p>A refund is an object of its own, as at the common providers: it has an id, the amount and the
 * caller's reference, and the payment's refunds can be listed. That list is how a caller finds out
 * whether a refund whose answer it lost was made, which the payment's refunded total alone can't
 * tell once a payment has more than one refund.
 *
 * <p>An authorization doesn't last: it carries the {@code expiresAt} the provider advertises,
 * {@link SimulatorSettings#authorizationLifetime()} after it was made, and from then on it is
 * {@link Status#EXPIRED}: a capture or a re-authorization is refused with {@code
 * authorization_expired}, and a void has nothing left to drop. {@link #reauthorize} extends a live
 * authorization by a whole lifetime from now, as the providers that offer authorization extension
 * do. Issuers don't always keep a hold for as long as the provider says, so the fault plan's
 * {@linkplain FaultPlan#earlyExpiryRate() early expiry rate} makes an authorization or
 * re-authorization lapse at a random moment before its advertised expiry, which {@code expiresAt}
 * doesn't show; {@link #expireNow} does the same on demand.
 *
 * <p>{@link SimulatorSettings#honorIdempotencyKeys()} can switch key handling off, which models a
 * provider (or a proxy in between) that doesn't deduplicate. The orchestrator must still never
 * charge twice.
 */
public final class PaymentProviderSimulator {

  /** Payment methods that decline, with the decline code a real provider would send. */
  static final Map<String, String> DECLINES =
      Map.of(
          "pm_card_declined", "card_declined",
          "pm_card_insufficient_funds", "insufficient_funds",
          "pm_card_expired", "expired_card");

  public enum Status {
    AUTHORIZED,
    DECLINED,
    CAPTURED,
    VOIDED,
    /** The hold lapsed before it was captured: no money was taken, and none can be now. */
    EXPIRED
  }

  public record Payment(
      String id,
      String reference,
      long amount,
      String currency,
      Status status,
      long captured,
      long refunded,
      String declineCode,
      Instant expiresAt) {}

  /** One refund of a captured payment. {@code reference} is the caller's id for it. */
  public record Refund(String id, String paymentId, String reference, long amount) {}

  /** One applied operation, kept so tests and the demo can audit exactly what was charged. */
  public record LedgerEntry(String operation, String paymentId, String reference, long amount) {}

  private record Stored(String fingerprint, Object result) {}

  /** The fault-plan operations of the first provider: {@code payment.authorize} and so on. */
  public static final String PRIMARY_OPERATIONS = "payment";

  private final String operations;
  private final String paymentIdPrefix;
  private final String refundIdPrefix;
  private final Faults faults;
  private final SimulatorSettings settings;
  private final Map<String, Payment> payments = new LinkedHashMap<>();
  private final Map<String, Refund> refunds = new LinkedHashMap<>();
  private final Map<String, Stored> idempotency = new HashMap<>();

  /** When each live authorization really lapses: its expiresAt, or earlier if it expires early. */
  private final Map<String, Instant> lapses = new HashMap<>();

  private final List<LedgerEntry> ledger = new ArrayList<>();
  private long nextId = 1;
  private long nextRefundId = 1;
  private final Clock defaultClock;
  private Clock clock;

  public PaymentProviderSimulator(Faults faults, SimulatorSettings settings) {
    this(faults, settings, Clock.systemUTC());
  }

  public PaymentProviderSimulator(Faults faults, SimulatorSettings settings, Clock clock) {
    this(PRIMARY_OPERATIONS, "pay_", "re_", faults, settings, clock);
  }

  /**
   * A provider of its own: its payments, refunds, keys and ledger are separate from any other
   * instance's, its ids start with {@code paymentIdPrefix} and {@code refundIdPrefix}, and its
   * fault plans are the {@code operations.*} ones ({@code operations.authorize}, {@code
   * operations.capture} and so on), so faults can be set on one provider without the other.
   */
  public PaymentProviderSimulator(
      String operations,
      String paymentIdPrefix,
      String refundIdPrefix,
      Faults faults,
      SimulatorSettings settings,
      Clock clock) {
    this.operations = operations;
    this.paymentIdPrefix = paymentIdPrefix;
    this.refundIdPrefix = refundIdPrefix;
    this.faults = faults;
    this.settings = settings;
    this.defaultClock = clock;
    this.clock = clock;
  }

  /**
   * Tells time by {@code clock} until the next {@link #reset}, so a test that moves its own clock
   * can cross an authorization's lifetime without waiting for it.
   */
  public synchronized void useClock(Clock clock) {
    this.clock = clock;
  }

  public Simulated<Payment> authorize(
      String idempotencyKey, String reference, long amount, String currency, String method) {
    requireText(reference, "reference");
    requireText(method, "payment_method");
    if (amount <= 0) {
      throw new SimulatorException(400, "invalid_amount", "amount must be positive");
    }
    String fp = "authorize|" + reference + "|" + amount + "|" + currency + "|" + method;
    return faults.run(
        op("authorize"),
        () ->
            idempotent(
                idempotencyKey,
                fp,
                () -> {
                  String id = paymentIdPrefix + String.format("%08d", nextId++);
                  String decline = DECLINES.get(method);
                  Instant now = clock.instant();
                  Instant expires =
                      decline == null ? now.plus(settings.authorizationLifetime()) : null;
                  Payment p =
                      new Payment(
                          id,
                          reference,
                          amount,
                          currency,
                          decline == null ? Status.AUTHORIZED : Status.DECLINED,
                          0,
                          0,
                          decline,
                          expires);
                  payments.put(id, p);
                  if (expires != null) {
                    lapses.put(id, lapse(op("authorize"), now, expires));
                  }
                  ledger.add(
                      new LedgerEntry(
                          decline == null ? "authorize" : "decline", id, reference, amount));
                  return p;
                }));
  }

  public Simulated<Payment> capture(String idempotencyKey, String paymentId, long amount) {
    return faults.run(
        op("capture"),
        () ->
            idempotent(
                idempotencyKey,
                "capture|" + paymentId + "|" + amount,
                () -> {
                  Payment p = find(paymentId);
                  if (p.status() == Status.CAPTURED) {
                    // Real providers refuse a second capture of the same authorization.
                    throw new SimulatorException(
                        409, "already_captured", "payment already captured");
                  }
                  requireLive(p);
                  if (amount <= 0 || amount > p.amount()) {
                    throw new SimulatorException(
                        400,
                        "invalid_amount",
                        "capture must be between 1 and the authorized amount");
                  }
                  Payment c = with(p, Status.CAPTURED, amount, 0);
                  lapses.remove(p.id());
                  ledger.add(new LedgerEntry("capture", p.id(), p.reference(), amount));
                  return c;
                }));
  }

  public Simulated<Payment> voidAuthorization(String idempotencyKey, String paymentId) {
    return faults.run(
        op("void"),
        () ->
            idempotent(
                idempotencyKey,
                "void|" + paymentId,
                () -> {
                  Payment p = find(paymentId);
                  if (p.status() == Status.VOIDED || p.status() == Status.EXPIRED) {
                    // Nothing is held any more, which is what the caller wants.
                    return p;
                  }
                  requireStatus(p, Status.AUTHORIZED);
                  Payment v = with(p, Status.VOIDED, 0, 0);
                  lapses.remove(p.id());
                  ledger.add(new LedgerEntry("void", p.id(), p.reference(), p.amount()));
                  return v;
                }));
  }

  /**
   * Extends a live authorization: it now lasts {@link SimulatorSettings#authorizationLifetime()}
   * from now, and its new {@code expiresAt} says so. The amount held doesn't change. Refused with
   * {@code authorization_expired} once the hold has lapsed, early or not, since there is nothing
   * left to extend, and with {@code invalid_state} for a payment that is not an authorization.
   */
  public Simulated<Payment> reauthorize(String idempotencyKey, String paymentId) {
    return faults.run(
        op("reauthorize"),
        () ->
            idempotent(
                idempotencyKey,
                "reauthorize|" + paymentId,
                () -> {
                  Payment p = find(paymentId);
                  requireLive(p);
                  Instant now = clock.instant();
                  Instant expires = now.plus(settings.authorizationLifetime());
                  Payment r =
                      new Payment(
                          p.id(),
                          p.reference(),
                          p.amount(),
                          p.currency(),
                          Status.AUTHORIZED,
                          0,
                          0,
                          p.declineCode(),
                          expires);
                  payments.put(p.id(), r);
                  lapses.put(p.id(), lapse(op("reauthorize"), now, expires));
                  ledger.add(new LedgerEntry("reauthorize", p.id(), p.reference(), p.amount()));
                  return r;
                }));
  }

  /**
   * The issuer drops the hold now, whatever its advertised expiry: the payment reads as {@link
   * Status#EXPIRED} from here on. Returns false when there was no live authorization to drop.
   */
  public synchronized boolean expireNow(String paymentId) {
    Payment p = payments.get(paymentId);
    if (p == null || current(p).status() != Status.AUTHORIZED) {
      return false;
    }
    lapses.put(paymentId, clock.instant());
    return true;
  }

  /**
   * Returns {@code amount} of a captured payment to the customer. {@code reference} is the caller's
   * id for the refund, kept on it so a later {@link #findRefunds} can match it.
   */
  public Simulated<Refund> refund(
      String idempotencyKey, String paymentId, long amount, String reference) {
    requireText(reference, "reference");
    return faults.run(
        op("refund"),
        () ->
            idempotent(
                idempotencyKey,
                "refund|" + paymentId + "|" + amount + "|" + reference,
                () -> {
                  Payment p = find(paymentId);
                  requireStatus(p, Status.CAPTURED);
                  if (amount <= 0 || amount > p.captured() - p.refunded()) {
                    throw new SimulatorException(
                        400, "invalid_amount", "refund exceeds the captured amount left");
                  }
                  with(p, Status.CAPTURED, p.captured(), p.refunded() + amount);
                  Refund r =
                      new Refund(
                          refundIdPrefix + String.format("%08d", nextRefundId++),
                          p.id(),
                          reference,
                          amount);
                  refunds.put(r.id(), r);
                  ledger.add(new LedgerEntry("refund", p.id(), p.reference(), amount));
                  return r;
                },
                r -> r));
  }

  /** Every refund of a payment, oldest first. */
  public Simulated<List<Refund>> findRefunds(String paymentId) {
    return faults.run(
        op("lookup"),
        () -> {
          synchronized (this) {
            find(paymentId);
            return refundsFor(paymentId);
          }
        });
  }

  public synchronized List<Refund> refundsFor(String paymentId) {
    return refunds.values().stream().filter(r -> r.paymentId().equals(paymentId)).toList();
  }

  /** Every payment ever created for a reference, oldest first. */
  public Simulated<List<Payment>> findByReference(String reference) {
    return faults.run(op("lookup"), () -> paymentsFor(reference));
  }

  public synchronized List<Payment> paymentsFor(String reference) {
    return payments.values().stream()
        .filter(p -> p.reference().equals(reference))
        .map(this::current)
        .toList();
  }

  public synchronized List<Payment> payments() {
    return payments.values().stream().map(this::current).toList();
  }

  public synchronized List<LedgerEntry> ledger() {
    return List.copyOf(ledger);
  }

  public synchronized List<LedgerEntry> ledgerFor(String reference) {
    return ledger.stream().filter(e -> e.reference().equals(reference)).toList();
  }

  public synchronized void reset() {
    payments.clear();
    refunds.clear();
    idempotency.clear();
    lapses.clear();
    ledger.clear();
    clock = defaultClock;
    nextId = 1;
    nextRefundId = 1;
  }

  /** The fault-plan operation {@code name} of this provider, such as {@code payment.capture}. */
  private String op(String name) {
    return operations + "." + name;
  }

  private Payment idempotent(String key, String fingerprint, Supplier<Payment> effect) {
    return idempotent(key, fingerprint, effect, this::current);
  }

  /**
   * Runs {@code effect} once per key. A replay returns {@code replay} of the first result, or
   * throws the first error again.
   */
  private synchronized <T> T idempotent(
      String key, String fingerprint, Supplier<T> effect, UnaryOperator<T> replay) {
    if (key == null || !settings.honorIdempotencyKeys()) {
      return effect.get();
    }
    Stored stored = idempotency.get(key);
    if (stored != null) {
      if (!stored.fingerprint().equals(fingerprint)) {
        throw new SimulatorException(
            400, "idempotency_error", "key was used with different parameters");
      }
      if (stored.result() instanceof SimulatorException e) {
        throw e;
      }
      @SuppressWarnings("unchecked")
      T first = (T) stored.result();
      return replay.apply(first);
    }
    try {
      T result = effect.get();
      idempotency.put(key, new Stored(fingerprint, result));
      return result;
    } catch (SimulatorException e) {
      idempotency.put(key, new Stored(fingerprint, e));
      throw e;
    }
  }

  /**
   * The payment as it is now, as the common providers report it on a replay or a lookup: an
   * authorization whose hold has lapsed by now reads, and from then on stays, {@link
   * Status#EXPIRED}.
   */
  private synchronized Payment current(Payment p) {
    Payment now = payments.getOrDefault(p.id(), p);
    Instant lapse = lapses.get(now.id());
    if (now.status() == Status.AUTHORIZED && lapse != null && !clock.instant().isBefore(lapse)) {
      lapses.remove(now.id());
      now = with(now, Status.EXPIRED, 0, 0);
    }
    return now;
  }

  private Payment find(String id) {
    Payment p = payments.get(id);
    if (p == null) {
      throw new SimulatorException(404, "not_found", "no such payment");
    }
    return current(p);
  }

  /**
   * When a hold made at {@code now} and advertised until {@code expires} really lapses: then, or,
   * as {@code operation}'s fault plan rolls, at a random moment before it.
   */
  private Instant lapse(String operation, Instant now, Instant expires) {
    OptionalDouble early = faults.earlyExpiry(operation);
    if (early.isEmpty()) {
      return expires;
    }
    long span = Duration.between(now, expires).toNanos();
    return now.plusNanos((long) (span * early.getAsDouble()));
  }

  /** A capture or extension needs a hold that is still there. */
  private static void requireLive(Payment p) {
    if (p.status() == Status.EXPIRED) {
      throw new SimulatorException(
          409, "authorization_expired", "the authorization expired at the issuer");
    }
    requireStatus(p, Status.AUTHORIZED);
  }

  private Payment with(Payment p, Status status, long captured, long refunded) {
    Payment n =
        new Payment(
            p.id(),
            p.reference(),
            p.amount(),
            p.currency(),
            status,
            captured,
            refunded,
            p.declineCode(),
            status == Status.AUTHORIZED || status == Status.EXPIRED ? p.expiresAt() : null);
    payments.put(p.id(), n);
    return n;
  }

  private static void requireStatus(Payment p, Status... allowed) {
    if (!Set.of(allowed).contains(p.status())) {
      throw new SimulatorException(
          409,
          "invalid_state",
          "payment is " + p.status().name().toLowerCase(java.util.Locale.ROOT));
    }
  }

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank() || value.length() > 128) {
      throw new SimulatorException(400, "invalid_request", field + " must be 1-128 characters");
    }
  }
}
