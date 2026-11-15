package com.example.order.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.example.order.exception.DocumentStorageException;

@Component
@ConditionalOnProperty(name = "app.storage.type", havingValue = "local", matchIfMissing = true)
public class LocalDocumentStorage implements DocumentStorage {

    private static final Logger log = LoggerFactory.getLogger(LocalDocumentStorage.class);

    private final StorageProperties properties;

    public LocalDocumentStorage(StorageProperties properties) {
        this.properties = properties;
    }

    @Override
    public String store(String key, InputStream inputStream, long contentLength, String contentType) {
        Path root = root();
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root)) {
            throw new DocumentStorageException("Invalid storage key", null);
        }
        try {
            Files.createDirectories(target.getParent());
            Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
            log.info("Stored document locally key={} bytes={}", key, contentLength);
            return key;
        } catch (IOException ex) {
            throw new DocumentStorageException("Could not write the document to local storage", ex);
        }
    }

    @Override
    public byte[] read(String key) {
        Path target = root().resolve(key).normalize();
        if (!target.startsWith(root())) {
            throw new DocumentStorageException("Invalid storage key", null);
        }
        try {
            return Files.readAllBytes(target);
        } catch (IOException ex) {
            throw new DocumentStorageException("Could not read the document from local storage", ex);
        }
    }

    @Override
    public String backend() {
        return "local";
    }

    private Path root() {
        String configured = properties.localDir() == null ? "./uploads" : properties.localDir();
        return Path.of(configured).toAbsolutePath().normalize();
    }
}
