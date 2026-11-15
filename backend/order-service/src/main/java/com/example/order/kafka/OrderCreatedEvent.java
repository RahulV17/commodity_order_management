package com.example.order.kafka;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Message published to the order-events topic after an order is persisted.
 * The instrument service maps this payload onto its own OrderEvent type.
 */
public record OrderCreatedEvent(
        String eventId,
        String eventType,
        String orderId,
        String instrumentId,
        BigDecimal quantity,
        String orderType,
        String createdBy,
        Instant occurredAt) {
}
