package io.github.laiwf94.payflow.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.laiwf94.payflow.order.Order;
import io.github.laiwf94.payflow.order.OrderState;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
   * Records that {@code order} (as it is after the change) went through {@code type}. The order's
   * version becomes the event's sequence number within the order.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public UUID write(String type, Order order, OrderState previous, Instant now) {
    UUID eventId = UUID.randomUUID();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("event_id", eventId);
    payload.put("event_type", type);
    payload.put("order_id", order.id());
    payload.put("merchant_id", order.merchantId());
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
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("can't serialise event", e);
    }
  }
}
