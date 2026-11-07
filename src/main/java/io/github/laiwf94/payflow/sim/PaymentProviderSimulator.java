package io.github.laiwf94.payflow.sim;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    VOIDED
  }

  public record Payment(
      String id,
      String reference,
      long amount,
      String currency,
      Status status,
      long captured,
      long refunded,
      String declineCode) {}

  /** One refund of a captured payment. {@code reference} is the caller's id for it. */
  public record Refund(String id, String paymentId, String reference, long amount) {}

  /** One applied operation, kept so tests and the demo can audit exactly what was charged. */
  public record LedgerEntry(String operation, String paymentId, String reference, long amount) {}

  private record Stored(String fingerprint, Object result) {}

  private final Faults faults;
  private final SimulatorSettings settings;
  private final Map<String, Payment> payments = new LinkedHashMap<>();
  private final Map<String, Refund> refunds = new LinkedHashMap<>();
  private final Map<String, Stored> idempotency = new HashMap<>();
  private final List<LedgerEntry> ledger = new ArrayList<>();
  private long nextId = 1;
  private long nextRefundId = 1;

  public PaymentProviderSimulator(Faults faults, SimulatorSettings settings) {
    this.faults = faults;
    this.settings = settings;
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
        "payment.authorize",
        () ->
            idempotent(
                idempotencyKey,
                fp,
                () -> {
                  String id = "pay_" + String.format("%08d", nextId++);
                  String decline = DECLINES.get(method);
                  Payment p =
                      new Payment(
                          id,
                          reference,
                          amount,
                          currency,
                          decline == null ? Status.AUTHORIZED : Status.DECLINED,
                          0,
                          0,
                          decline);
                  payments.put(id, p);
                  ledger.add(
                      new LedgerEntry(
                          decline == null ? "authorize" : "decline", id, reference, amount));
                  return p;
                }));
  }

  public Simulated<Payment> capture(String idempotencyKey, String paymentId, long amount) {
    return faults.run(
        "payment.capture",
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
                  requireStatus(p, Status.AUTHORIZED);
                  if (amount <= 0 || amount > p.amount()) {
                    throw new SimulatorException(
                        400,
                        "invalid_amount",
                        "capture must be between 1 and the authorized amount");
                  }
                  Payment c = with(p, Status.CAPTURED, amount, 0);
                  ledger.add(new LedgerEntry("capture", p.id(), p.reference(), amount));
                  return c;
                }));
  }

  public Simulated<Payment> voidAuthorization(String idempotencyKey, String paymentId) {
    return faults.run(
        "payment.void",
        () ->
            idempotent(
                idempotencyKey,
                "void|" + paymentId,
                () -> {
                  Payment p = find(paymentId);
                  if (p.status() == Status.VOIDED) {
                    return p;
                  }
                  requireStatus(p, Status.AUTHORIZED);
                  Payment v = with(p, Status.VOIDED, 0, 0);
                  ledger.add(new LedgerEntry("void", p.id(), p.reference(), p.amount()));
                  return v;
                }));
  }

  /**
   * Returns {@code amount} of a captured payment to the customer. {@code reference} is the caller's
   * id for the refund, kept on it so a later {@link #findRefunds} can match it.
   */
  public Simulated<Refund> refund(
      String idempotencyKey, String paymentId, long amount, String reference) {
    requireText(reference, "reference");
    return faults.run(
        "payment.refund",
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
                          "re_" + String.format("%08d", nextRefundId++), p.id(), reference, amount);
                  refunds.put(r.id(), r);
                  ledger.add(new LedgerEntry("refund", p.id(), p.reference(), amount));
                  return r;
                },
                r -> r));
  }

  /** Every refund of a payment, oldest first. */
  public Simulated<List<Refund>> findRefunds(String paymentId) {
    return faults.run(
        "payment.lookup",
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
    return faults.run("payment.lookup", () -> paymentsFor(reference));
  }

  public synchronized List<Payment> paymentsFor(String reference) {
    return payments.values().stream().filter(p -> p.reference().equals(reference)).toList();
  }

  public synchronized List<Payment> payments() {
    return List.copyOf(payments.values());
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
    ledger.clear();
    nextId = 1;
    nextRefundId = 1;
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

  /** A replay returns the payment as it is now, as the common providers do. */
  private Payment current(Payment p) {
    return payments.getOrDefault(p.id(), p);
  }

  private Payment find(String id) {
    Payment p = payments.get(id);
    if (p == null) {
      throw new SimulatorException(404, "not_found", "no such payment");
    }
    return p;
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
            p.declineCode());
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
