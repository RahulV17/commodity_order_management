package com.example.instrument.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Event consumed from the order-events topic. Explicit JSON property names keep
 * deserialization independent of compiler parameter metadata.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderEvent(
        String eventId,
        String eventType,
        String orderId,
        String instrumentId,
        BigDecimal quantity,
        String orderType,
        String createdBy,
        Instant occurredAt) {

    @JsonCreator
    public OrderEvent(
            @JsonProperty("eventId") String eventId,
            @JsonProperty("eventType") String eventType,
            @JsonProperty("orderId") String orderId,
            @JsonProperty("instrumentId") String instrumentId,
            @JsonProperty("quantity") BigDecimal quantity,
            @JsonProperty("orderType") String orderType,
            @JsonProperty("createdBy") String createdBy,
            @JsonProperty("occurredAt") Instant occurredAt) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.orderId = orderId;
        this.instrumentId = instrumentId;
        this.quantity = quantity;
        this.orderType = orderType;
        this.createdBy = createdBy;
        this.occurredAt = occurredAt;
    }
}
