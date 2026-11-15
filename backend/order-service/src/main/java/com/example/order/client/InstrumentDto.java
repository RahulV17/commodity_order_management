package com.example.order.client;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record InstrumentDto(
        String id,
        String symbol,
        String name,
        String category,
        BigDecimal indicativePrice,
        boolean active,
        long activityCount) {
}
