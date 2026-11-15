package com.example.order.mapper;

import org.springframework.stereotype.Component;

import com.example.order.dto.OrderResponse;
import com.example.order.entity.Order;

@Component
public class OrderMapper {

    public OrderResponse toResponse(Order order) {
        return new OrderResponse(
                order.getId(),
                order.getOrderNumber(),
                order.getInstrumentId(),
                order.getInstrumentSymbol(),
                order.getInstrumentName(),
                order.getOrderType(),
                order.getQuantity(),
                order.getPrice(),
                order.getStatus(),
                order.getRemarks(),
                order.getRejectionReason(),
                order.getCreatedBy(),
                order.getCreatedAt(),
                order.getUpdatedAt());
    }
}
