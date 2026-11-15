package com.example.instrument.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.example.instrument.document.InstrumentCategory;
import com.example.instrument.dto.InstrumentResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

@ExtendWith(MockitoExtension.class)
class InstrumentCacheServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private ObjectMapper objectMapper;
    private InstrumentCacheService cacheService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        cacheService = new InstrumentCacheService(redisTemplate, objectMapper, 300);
    }

    @Test
    void putStoresJsonWithFiveMinuteTtl() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        InstrumentResponse response = response();

        cacheService.put(response);

        verify(valueOperations).set(
                eq("instrument:GOLD-001"),
                anyString(),
                eq(Duration.ofSeconds(300)));
    }

    @Test
    void getReturnsCachedValueOnHit() throws Exception {
        InstrumentResponse response = response();
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("instrument:GOLD-001"))
                .thenReturn(objectMapper.writeValueAsString(response));

        Optional<InstrumentResponse> cached = cacheService.get("GOLD-001");

        assertThat(cached).isPresent();
        assertThat(cached.orElseThrow().symbol()).isEqualTo("GOLD");
    }

    @Test
    void evictDeletesKey() {
        when(redisTemplate.delete("instrument:GOLD-001")).thenReturn(true);

        cacheService.evict("GOLD-001");

        verify(redisTemplate).delete("instrument:GOLD-001");
    }

    @Test
    void redisFailureIsTreatedAsCacheMiss() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("instrument:GOLD-001"))
                .thenThrow(new RedisConnectionFailureException("Redis is down"));

        Optional<InstrumentResponse> cached = cacheService.get("GOLD-001");

        assertThat(cached).isEmpty();
    }

    private InstrumentResponse response() {
        return new InstrumentResponse("GOLD-001", "GOLD", "Gold", InstrumentCategory.PRECIOUS_METAL,
                new BigDecimal("15000"), true, 2, null, null, null);
    }
}
