package com.example.instrument.dto;

import java.math.BigDecimal;

import com.example.instrument.document.Instrument;
import com.example.instrument.document.InstrumentCategory;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Read model returned by the API and stored in Redis. It is intentionally a
 * separate type from the Mongo document so cache and API contracts are stable.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InstrumentResponse(
        String id,
        String symbol,
        String name,
        InstrumentCategory category,
        BigDecimal indicativePrice,
        boolean active,
        long activityCount,
        java.time.Instant lastOrderAt,
        java.time.Instant createdAt,
        java.time.Instant updatedAt) {

    public static InstrumentResponse from(Instrument instrument) {
        return new InstrumentResponse(
                instrument.getId(),
                instrument.getSymbol(),
                instrument.getName(),
                instrument.getCategory(),
                instrument.getIndicativePrice(),
                instrument.isActive(),
                instrument.getActivityCount(),
                instrument.getLastOrderAt(),
                instrument.getCreatedAt(),
                instrument.getUpdatedAt());
    }
}
