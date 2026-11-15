package com.example.instrument.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.example.instrument.cache.InstrumentCacheService;
import com.example.instrument.document.Instrument;
import com.example.instrument.dto.InstrumentRequest;
import com.example.instrument.dto.InstrumentResponse;
import com.example.instrument.dto.UpdateInstrumentRequest;
import com.example.instrument.exception.DuplicateInstrumentException;
import com.example.instrument.exception.InstrumentNotFoundException;
import com.example.instrument.repository.InstrumentRepository;

@Service
public class InstrumentService {

    private static final Logger log = LoggerFactory.getLogger(InstrumentService.class);

    private final InstrumentRepository instrumentRepository;
    private final InstrumentCacheService cacheService;
    private final MongoTemplate mongoTemplate;

    public InstrumentService(InstrumentRepository instrumentRepository,
                             InstrumentCacheService cacheService,
                             MongoTemplate mongoTemplate) {
        this.instrumentRepository = instrumentRepository;
        this.cacheService = cacheService;
        this.mongoTemplate = mongoTemplate;
    }

    public List<InstrumentResponse> findAll(String search, Boolean active) {
        List<Instrument> instruments;
        if (StringUtils.hasText(search)) {
            String term = search.trim();
            instruments = instrumentRepository
                    .findByNameContainingIgnoreCaseOrSymbolContainingIgnoreCase(
                            term, term, Sort.by(Sort.Direction.ASC, "symbol"));
        } else {
            instruments = instrumentRepository.findAll(Sort.by(Sort.Direction.ASC, "symbol"));
        }
        return instruments.stream()
                .filter(instrument -> active == null || instrument.isActive() == active)
                .map(InstrumentResponse::from)
                .toList();
    }

    /**
     * Cache-aside read: Redis first, MongoDB on a miss, then populate Redis.
     */
    public InstrumentResponse findById(String id) {
        Optional<InstrumentResponse> cached = cacheService.get(id);
        if (cached.isPresent()) {
            log.info("Cache HIT for instrument {}", id);
            return cached.get();
        }

        log.info("Cache MISS for instrument {} - reading MongoDB", id);
        Instrument instrument = instrumentRepository.findById(id)
                .orElseThrow(() -> new InstrumentNotFoundException(id));
        InstrumentResponse response = InstrumentResponse.from(instrument);
        cacheService.put(response);
        return response;
    }

    public InstrumentResponse create(InstrumentRequest request) {
        if (instrumentRepository.existsById(request.id())) {
            throw new DuplicateInstrumentException("Instrument id already exists: " + request.id());
        }
        if (instrumentRepository.existsBySymbol(request.symbol())) {
            throw new DuplicateInstrumentException("Instrument symbol already exists: " + request.symbol());
        }

        Instrument instrument = new Instrument();
        instrument.setId(request.id());
        instrument.setSymbol(request.symbol());
        instrument.setName(request.name());
        instrument.setCategory(request.category());
        instrument.setIndicativePrice(request.indicativePrice());
        instrument.setActive(request.active() == null || request.active());

        Instrument saved = instrumentRepository.save(instrument);
        cacheService.evict(saved.getId());
        log.info("Created instrument id={} symbol={} by request", saved.getId(), saved.getSymbol());
        return InstrumentResponse.from(saved);
    }

    public InstrumentResponse update(String id, UpdateInstrumentRequest request) {
        Instrument instrument = instrumentRepository.findById(id)
                .orElseThrow(() -> new InstrumentNotFoundException(id));

        instrumentRepository.findBySymbol(request.symbol())
                .filter(existing -> !existing.getId().equals(id))
                .ifPresent(existing -> {
                    throw new DuplicateInstrumentException(
                            "Instrument symbol already exists: " + request.symbol());
                });

        instrument.setSymbol(request.symbol());
        instrument.setName(request.name());
        instrument.setCategory(request.category());
        instrument.setIndicativePrice(request.indicativePrice());

        Instrument saved = instrumentRepository.save(instrument);
        cacheService.evict(id);
        log.info("Updated instrument id={} symbol={}", saved.getId(), saved.getSymbol());
        return InstrumentResponse.from(saved);
    }

    public InstrumentResponse updateStatus(String id, boolean active) {
        Instrument instrument = instrumentRepository.findById(id)
                .orElseThrow(() -> new InstrumentNotFoundException(id));
        instrument.setActive(active);
        Instrument saved = instrumentRepository.save(instrument);
        cacheService.evict(id);
        log.info("Instrument {} status changed to active={}", id, active);
        return InstrumentResponse.from(saved);
    }

    /**
     * Kafka consumer entry point. The counter is incremented atomically with a
     * Mongo $inc so concurrent consumer instances cannot lose updates.
     */
    public void incrementOrderActivity(String instrumentId, BigDecimal quantity, String orderNumber) {
        Update update = new Update()
                .inc("activityCount", 1)
                .set("lastOrderAt", Instant.now());
        var result = mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(instrumentId)),
                update,
                Instrument.class);

        if (result.getMatchedCount() == 0) {
            log.warn("Received order event for unknown instrument {} (order {})", instrumentId, orderNumber);
            return;
        }

        cacheService.evict(instrumentId);
        log.info("Instrument {} activity counter incremented for order {} (quantity {})",
                instrumentId, orderNumber, quantity);
    }
}
