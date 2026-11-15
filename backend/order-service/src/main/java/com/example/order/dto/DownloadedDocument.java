package com.example.order.dto;

public record DownloadedDocument(String fileName, String contentType, byte[] content) {
}
