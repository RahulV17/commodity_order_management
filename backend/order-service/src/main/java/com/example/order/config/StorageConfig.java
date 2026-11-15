package com.example.order.config;

import java.net.URI;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import com.example.order.storage.StorageProperties;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

@Configuration
public class StorageConfig {

    @Bean
    @ConditionalOnProperty(name = "app.storage.type", havingValue = "s3")
    public S3Client s3Client(StorageProperties properties) {
        String region = StringUtils.hasText(properties.region()) ? properties.region() : "us-east-1";
        S3ClientBuilder builder = S3Client.builder().region(Region.of(region));

        // Endpoint override enables LocalStack/MinIO demos. Path-style access is
        // required by most non-AWS S3-compatible endpoints.
        if (StringUtils.hasText(properties.endpoint())) {
            builder.endpointOverride(URI.create(properties.endpoint())).forcePathStyle(true);
        }
        return builder.build();
    }
}
