package com.example.order.dto;

import java.math.BigDecimal;

import com.example.order.entity.OrderType;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateOrderRequest(
        @NotBlank(message = "Instrument is required")
        @Size(max = 32, message = "Instrument id is too long")
        String instrumentId,

        @NotNull(message = "Order type is required")
        OrderType orderType,

        @NotNull(message = "Quantity is required")
        @DecimalMin(value = "0.0001", message = "Quantity must be positive")
        @Digits(integer = 15, fraction = 4, message = "Quantity supports at most 4 decimals")
        BigDecimal quantity,

        @NotNull(message = "Price is required")
        @DecimalMin(value = "0.0001", message = "Price must be positive")
        @Digits(integer = 15, fraction = 4, message = "Price supports at most 4 decimals")
        BigDecimal price,

        @Size(max = 500, message = "Remarks must not exceed 500 characters")
        String remarks) {
}
