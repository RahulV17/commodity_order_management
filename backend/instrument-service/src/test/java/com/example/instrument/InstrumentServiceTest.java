package com.example.instrument;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;

import com.example.instrument.cache.InstrumentCacheService;
import com.example.instrument.document.Instrument;
import com.example.instrument.document.InstrumentCategory;
import com.example.instrument.dto.InstrumentResponse;
import com.example.instrument.exception.InstrumentNotFoundException;
import com.example.instrument.repository.InstrumentRepository;
import com.example.instrument.service.InstrumentService;
import com.mongodb.client.result.UpdateResult;

@ExtendWith(MockitoExtension.class)
class InstrumentServiceTest {

    @Mock
    private InstrumentRepository instrumentRepository;

    @Mock
    private InstrumentCacheService cacheService;

    @Mock
    private MongoTemplate mongoTemplate;

    private InstrumentService instrumentService;

    @BeforeEach
    void setUp() {
        instrumentService = new InstrumentService(instrumentRepository, cacheService, mongoTemplate);
    }

    @Test
    void findByIdReturnsCachedValueWithoutReadingMongo() {
        InstrumentResponse cached = new InstrumentResponse(
                "GOLD-001", "GOLD", "Gold", InstrumentCategory.PRECIOUS_METAL,
                new BigDecimal("15000"), true, 3, null, null, null);
        when(cacheService.get("GOLD-001")).thenReturn(Optional.of(cached));

        InstrumentResponse result = instrumentService.findById("GOLD-001");

        assertThat(result.symbol()).isEqualTo("GOLD");
        verify(instrumentRepository, never()).findById(anyString());
    }

    @Test
    void findByIdReadsMongoAndPopulatesCacheOnMiss() {
        Instrument instrument = new Instrument();
        instrument.setId("GOLD-001");
        instrument.setSymbol("GOLD");
        instrument.setName("Gold");
        instrument.setCategory(InstrumentCategory.PRECIOUS_METAL);
        instrument.setIndicativePrice(new BigDecimal("15000"));
        instrument.setActive(true);

        when(cacheService.get("GOLD-001")).thenReturn(Optional.empty());
        when(instrumentRepository.findById("GOLD-001")).thenReturn(Optional.of(instrument));

        InstrumentResponse result = instrumentService.findById("GOLD-001");

        assertThat(result.id()).isEqualTo("GOLD-001");
        verify(cacheService).put(any(InstrumentResponse.class));
    }

    @Test
    void findByIdThrowsWhenInstrumentDoesNotExist() {
        when(cacheService.get("UNKNOWN")).thenReturn(Optional.empty());
        when(instrumentRepository.findById("UNKNOWN")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> instrumentService.findById("UNKNOWN"))
                .isInstanceOf(InstrumentNotFoundException.class)
                .hasMessageContaining("UNKNOWN");
    }

    @Test
    void updateStatusPersistsAndEvictsCache() {
        Instrument instrument = new Instrument();
        instrument.setId("GOLD-001");
        instrument.setSymbol("GOLD");
        instrument.setActive(true);

        when(instrumentRepository.findById("GOLD-001")).thenReturn(Optional.of(instrument));
        when(instrumentRepository.save(instrument)).thenReturn(instrument);

        InstrumentResponse result = instrumentService.updateStatus("GOLD-001", false);

        assertThat(result.active()).isFalse();
        verify(cacheService).evict("GOLD-001");
    }

    @Test
    void incrementOrderActivityUsesAtomicMongoUpdateAndEvictsCache() {
        UpdateResult updateResult = mock(UpdateResult.class);
        when(updateResult.getMatchedCount()).thenReturn(1L);
        when(mongoTemplate.updateFirst(any(), any(), any(Class.class))).thenReturn(updateResult);

        instrumentService.incrementOrderActivity("GOLD-001", new BigDecimal("10"), "ORD-TEST");

        verify(mongoTemplate).updateFirst(any(), any(), any(Class.class));
        verify(cacheService).evict("GOLD-001");
    }
}
