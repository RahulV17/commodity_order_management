package com.example.order.kafka;

import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.example.order.entity.Order;

@Component
public class OrderEventProducer {

    private static final Logger log = LoggerFactory.getLogger(OrderEventProducer.class);
    private static final String EVENT_TYPE = "ORDER_CREATED";

    private final KafkaTemplate<String, OrderCreatedEvent> kafkaTemplate;
    private final String topic;

    public OrderEventProducer(KafkaTemplate<String, OrderCreatedEvent> kafkaTemplate,
                              @Value("${app.kafka.order-events-topic:order-events}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    /**
     * Publishing is best-effort and happens after the database save. A broker
     * outage must not lose the order; production systems should use a
     * transactional outbox and a relay for guaranteed delivery.
     */
    public void publishOrderCreated(Order order) {
        OrderCreatedEvent event = new OrderCreatedEvent(
                UUID.randomUUID().toString(),
                EVENT_TYPE,
                order.getOrderNumber(),
                order.getInstrumentId(),
                order.getQuantity(),
                order.getOrderType().name(),
                order.getCreatedBy(),
                Instant.now());

        try {
            kafkaTemplate.send(topic, order.getInstrumentId(), event)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("Failed to publish {} for order {}: {}",
                                    EVENT_TYPE, order.getOrderNumber(), ex.getMessage(), ex);
                            return;
                        }
                        var metadata = result.getRecordMetadata();
                        log.info("Published {} orderId={} topic={} partition={} offset={}",
                                EVENT_TYPE, order.getOrderNumber(), metadata.topic(),
                                metadata.partition(), metadata.offset());
                    });
        } catch (Exception ex) {
            log.error("Could not enqueue {} for order {}: {}",
                    EVENT_TYPE, order.getOrderNumber(), ex.getMessage(), ex);
        }
    }
}
