package com.example.instrument.dto;

import java.math.BigDecimal;

import com.example.instrument.document.InstrumentCategory;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateInstrumentRequest(
        @NotBlank(message = "Symbol is required")
        @Pattern(regexp = "^[A-Z0-9]{2,16}$", message = "Symbol must be 2-16 uppercase letters or digits")
        String symbol,

        @NotBlank(message = "Name is required")
        @Size(max = 100, message = "Name must not exceed 100 characters")
        String name,

        @NotNull(message = "Category is required")
        InstrumentCategory category,

        @NotNull(message = "Indicative price is required")
        @DecimalMin(value = "0.0001", message = "Indicative price must be positive")
        @Digits(integer = 15, fraction = 4, message = "Indicative price supports at most 4 decimals")
        BigDecimal indicativePrice) {
}
