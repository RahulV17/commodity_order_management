package com.example.instrument.dto;

import jakarta.validation.constraints.NotNull;

public record StatusUpdateRequest(
        @NotNull(message = "active is required")
        Boolean active) {
}
