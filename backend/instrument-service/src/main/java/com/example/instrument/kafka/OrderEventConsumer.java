package com.example.instrument.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.example.instrument.dto.OrderEvent;
import com.example.instrument.service.InstrumentService;

/**
 * Consumer side of the asynchronous order flow.
 *
 * group-id = instrument-service. Every instance with the same group id shares
 * partitions, which is how the consumer group scales horizontally.
 */
@Component
public class OrderEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderEventConsumer.class);

    private final InstrumentService instrumentService;

    public OrderEventConsumer(InstrumentService instrumentService) {
        this.instrumentService = instrumentService;
    }

    @KafkaListener(
            topics = "${app.kafka.order-events-topic:order-events}",
            groupId = "${spring.kafka.consumer.group-id:instrument-service}")
    public void onOrderEvent(OrderEvent event) {
        if (event == null || !"ORDER_CREATED".equals(event.eventType())) {
            log.debug("Ignoring unsupported order event: {}", event);
            return;
        }

        try {
            log.info("Consumed ORDER_CREATED eventId={} orderId={} instrumentId={}",
                    event.eventId(), event.orderId(), event.instrumentId());
            instrumentService.incrementOrderActivity(
                    event.instrumentId(), event.quantity(), event.orderId());
        } catch (Exception ex) {
            // A poison message must not block the consumer. Production systems should
            // use retries and a dead-letter topic; the trade-off is documented in README.
            log.error("Failed to process order eventId={} orderId={}: {}",
                    event.eventId(), event.orderId(), ex.getMessage(), ex);
        }
    }
}
