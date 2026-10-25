package io.github.laiwf94.payflow.timeline;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.laiwf94.payflow.outbox.OutboxRelay;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes order events into the timeline. Delivery is at least once; {@link TimelineProjection}
 * makes repeats harmless. A record that can't be parsed goes to the dead-letter topic instead of
 * blocking the partition.
 */
@Component
class TimelineConsumer {

  private static final Logger log = LoggerFactory.getLogger(TimelineConsumer.class);

  private final TimelineProjection projection;
  private final ObjectMapper json;

  TimelineConsumer(TimelineProjection projection, ObjectMapper json) {
    this.projection = projection;
    this.json = json.copy().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
  }

  @KafkaListener(
      id = "timeline",
      topics = "${payflow.outbox.topic:payflow.order-events}",
      groupId = "payflow-timeline",
      autoStartup = "${payflow.timeline.enabled:true}")
  void onEvent(ConsumerRecord<String, String> record) {
    String correlationId = header(record, OutboxRelay.HEADER_CORRELATION_ID);
    if (correlationId != null) {
      MDC.put("correlation_id", correlationId);
    }
    try {
      OrderEvent event = parse(record.value());
      MDC.put("order_id", event.orderId().toString());
      boolean applied = projection.apply(event);
      log.debug("{} {} for order", applied ? "applied" : "skipped duplicate", event.eventType());
    } finally {
      MDC.remove("correlation_id");
      MDC.remove("order_id");
    }
  }

  OrderEvent parse(String value) {
    if (value == null) {
      throw new MalformedEventException("empty record");
    }
    OrderEvent e;
    try {
      e = json.readValue(value, OrderEvent.class);
    } catch (IOException ex) {
      throw new MalformedEventException("not an order event");
    }
    if (e == null || !e.complete()) {
      throw new MalformedEventException("order event is missing fields");
    }
    return e;
  }

  private static String header(ConsumerRecord<String, String> record, String name) {
    Header h = record.headers().lastHeader(name);
    return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
  }
}
