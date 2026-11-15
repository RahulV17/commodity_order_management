package com.example.order.storage;

import java.io.InputStream;
import java.net.URI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.example.order.exception.DocumentStorageException;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * S3 implementation. Credentials come from the default AWS provider chain
 * (environment variables, profile or IAM role); none are hard-coded.
 */
@Component
@ConditionalOnProperty(name = "app.storage.type", havingValue = "s3")
public class S3DocumentStorage implements DocumentStorage {

    private static final Logger log = LoggerFactory.getLogger(S3DocumentStorage.class);

    private final S3Client s3Client;
    private final StorageProperties properties;

    public S3DocumentStorage(S3Client s3Client, StorageProperties properties) {
        this.s3Client = s3Client;
        this.properties = properties;
    }

    @Override
    public String store(String key, InputStream inputStream, long contentLength, String contentType) {
        if (!StringUtils.hasText(properties.bucket())) {
            throw new DocumentStorageException("AWS_S3_BUCKET is not configured", null);
        }
        try {
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(properties.bucket())
                    .key(key)
                    .contentType(contentType)
                    .contentLength(contentLength)
                    .build();
            s3Client.putObject(request, RequestBody.fromInputStream(inputStream, contentLength));
            log.info("Stored document in S3 bucket={} key={} bytes={}",
                    properties.bucket(), key, contentLength);
            return key;
        } catch (S3Exception ex) {
            throw new DocumentStorageException("S3 upload failed: " + ex.awsErrorDetails().errorMessage(), ex);
        }
    }

    @Override
    public byte[] read(String key) {
        if (!StringUtils.hasText(properties.bucket())) {
            throw new DocumentStorageException("AWS_S3_BUCKET is not configured", null);
        }
        try {
            GetObjectRequest request = GetObjectRequest.builder()
                    .bucket(properties.bucket())
                    .key(key)
                    .build();
            return s3Client.getObjectAsBytes(request).asByteArray();
        } catch (S3Exception ex) {
            throw new DocumentStorageException("S3 download failed: " + ex.awsErrorDetails().errorMessage(), ex);
        }
    }

    @Override
    public String backend() {
        return "s3";
    }
}
