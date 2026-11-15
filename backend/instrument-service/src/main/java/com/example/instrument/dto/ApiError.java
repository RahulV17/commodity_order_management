package com.example.instrument.dto;

import java.time.Instant;
import java.util.Map;

public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        String requestId,
        Map<String, String> fieldErrors) {

    public static ApiError of(int status, String error, String message, String path,
                              String requestId, Map<String, String> fieldErrors) {
        return new ApiError(Instant.now(), status, error, message, path, requestId, fieldErrors);
    }
}
