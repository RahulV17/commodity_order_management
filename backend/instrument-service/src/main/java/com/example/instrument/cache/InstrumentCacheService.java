package com.example.instrument.cache;

import java.time.Duration;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.example.instrument.dto.InstrumentResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Cache-aside helper for instrument-by-id lookups.
 *
 * Redis failures are deliberately swallowed: cache is an optimisation, not a
 * source of truth. If Redis is unavailable the service still answers from
 * MongoDB and logs a warning.
 */
@Service
public class InstrumentCacheService {

    private static final Logger log = LoggerFactory.getLogger(InstrumentCacheService.class);
    private static final String KEY_PREFIX = "instrument:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final long ttlSeconds;

    public InstrumentCacheService(StringRedisTemplate redisTemplate,
                                  ObjectMapper objectMapper,
                                  @Value("${app.cache.instrument-ttl-seconds:300}") long ttlSeconds) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.ttlSeconds = ttlSeconds;
    }

    public Optional<InstrumentResponse> get(String instrumentId) {
        String key = key(instrumentId);
        try {
            String payload = redisTemplate.opsForValue().get(key);
            if (payload == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(payload, InstrumentResponse.class));
        } catch (Exception ex) {
            log.warn("Redis read failed for key {}: {}", key, ex.getMessage());
            return Optional.empty();
        }
    }

    public void put(InstrumentResponse instrument) {
        String key = key(instrument.id());
        try {
            String payload = objectMapper.writeValueAsString(instrument);
            redisTemplate.opsForValue().set(key, payload, Duration.ofSeconds(ttlSeconds));
        } catch (Exception ex) {
            log.warn("Redis write failed for key {}: {}", key, ex.getMessage());
        }
    }

    public void evict(String instrumentId) {
        String key = key(instrumentId);
        try {
            Boolean deleted = redisTemplate.delete(key);
            log.info("Cache eviction for {} (deleted={})", key, deleted);
        } catch (Exception ex) {
            log.warn("Redis eviction failed for key {}: {}", key, ex.getMessage());
        }
    }

    private String key(String instrumentId) {
        return KEY_PREFIX + instrumentId;
    }
}
