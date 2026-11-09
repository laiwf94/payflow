package io.github.laiwf94.payflow.outbox;

import io.github.laiwf94.payflow.order.Order;
import io.github.laiwf94.payflow.order.OrderState;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes order events into the outbox. It refuses to run outside a transaction: an event written on
 * its own could describe a change that then rolls back, or a change could commit without it.
 */
@Component
public class OutboxWriter {

  private final JdbcClient jdbc;
  private final ObjectMapper json;
  private final OutboxSignal signal;

  public OutboxWriter(JdbcClient jdbc, ObjectMapper json, OutboxSignal signal) {
    this.jdbc = jdbc;
    this.json = json;
    this.signal = signal;
  }

  /**
   * What a refund event says about its refund, as it is after the change. {@code lines} is empty
   * for a refund of an amount; {@code failureCode} is set only for a refused one.
   */
  public record RefundDetails(
      UUID id, long amount, List<?> lines, String state, String failureCode) {
    public RefundDetails {
      lines = lines == null ? List.of() : List.copyOf(lines);
    }
  }

  /**
   * Records that {@code order} (as it is after the change) went through {@code type}. The order's
   * version becomes the event's sequence number within the order.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public UUID write(String type, Order order, OrderState previous, Instant now) {
    return write(type, order, previous, null, now);
  }

  /**
   * Records an event about one of {@code order}'s refunds. It goes into the order's stream, under
   * the order's version, so a consumer sees refunds and the order changes they cause in the order
   * they happened.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public UUID writeRefund(String type, Order order, RefundDetails refund, Instant now) {
    return write(type, order, null, refund, now);
  }

  private UUID write(
      String type, Order order, OrderState previous, RefundDetails refund, Instant now) {
    UUID eventId = UUID.randomUUID();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("event_id", eventId);
    payload.put("event_type", type);
    payload.put("order_id", order.id());
    payload.put("merchant_id", order.merchantId());
    if (order.merchantReference() != null) {
      payload.put("merchant_reference", order.merchantReference());
    }
    payload.put("seq", order.version());
    payload.put("state", order.state());
    if (previous != null) {
      payload.put("previous_state", previous);
    }
    payload.put("amount", order.amount().minorUnits());
    payload.put("currency", order.amount().currencyCode());
    if (order.failureCode() != null) {
      payload.put("failure_code", order.failureCode());
    }
    if (refund != null) {
      Map<String, Object> r = new LinkedHashMap<>();
      r.put("id", refund.id());
      r.put("amount", refund.amount());
      if (!refund.lines().isEmpty()) {
        r.put("lines", refund.lines());
      }
      r.put("state", refund.state());
      if (refund.failureCode() != null) {
        r.put("failure_code", refund.failureCode());
      }
      payload.put("refund", r);
    }
    payload.put("occurred_at", now.atOffset(ZoneOffset.UTC).toString());
    jdbc.sql(
            """
            INSERT INTO outbox (event_id, aggregate_id, aggregate_seq, event_type, payload,
                correlation_id, created_at)
            VALUES (:eventId, :aggregateId, :seq, :type, CAST(:payload AS jsonb), :correlationId, :now)
            """)
        .param("eventId", eventId)
        .param("aggregateId", order.id())
        .param("seq", order.version())
        .param("type", type)
        .param("payload", write(payload))
        .param("correlationId", order.correlationId())
        .param("now", now.atOffset(ZoneOffset.UTC))
        .update();
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            signal.wakeUp();
          }
        });
    return eventId;
  }

  private String write(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (JacksonException e) {
      throw new IllegalStateException("can't serialise event", e);
    }
  }
}
