package com.example.order.dto;

import java.time.Instant;

import com.example.order.entity.OrderDocument;

public record DocumentResponse(
        Long id,
        String fileName,
        String contentType,
        long sizeBytes,
        String uploadedBy,
        Instant uploadedAt) {

    public static DocumentResponse from(OrderDocument document) {
        return new DocumentResponse(
                document.getId(),
                document.getFileName(),
                document.getContentType(),
                document.getSizeBytes(),
                document.getUploadedBy(),
                document.getUploadedAt());
    }
}
