package com.example.order.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.storage")
public record StorageProperties(
        String type,
        String localDir,
        String bucket,
        String region,
        String endpoint) {
}
