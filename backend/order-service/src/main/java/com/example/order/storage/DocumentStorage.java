package com.example.order.storage;

import java.io.InputStream;

/**
 * Storage abstraction with two interchangeable implementations:
 * local filesystem (default, works without AWS credentials) and S3.
 */
public interface DocumentStorage {

    String store(String key, InputStream inputStream, long contentLength, String contentType);

    byte[] read(String key);

    String backend();
}
