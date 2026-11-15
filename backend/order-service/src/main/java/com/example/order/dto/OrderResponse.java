package com.example.order.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.example.order.entity.OrderStatus;
import com.example.order.entity.OrderType;

public record OrderResponse(
        Long id,
        String orderNumber,
        String instrumentId,
        String instrumentSymbol,
        String instrumentName,
        OrderType orderType,
        BigDecimal quantity,
        BigDecimal price,
        OrderStatus status,
        String remarks,
        String rejectionReason,
        String createdBy,
        Instant createdAt,
        Instant updatedAt) {
}
