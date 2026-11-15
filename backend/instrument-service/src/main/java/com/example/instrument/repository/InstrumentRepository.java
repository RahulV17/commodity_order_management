package com.example.instrument.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.repository.MongoRepository;

import com.example.instrument.document.Instrument;

public interface InstrumentRepository extends MongoRepository<Instrument, String> {

    boolean existsBySymbol(String symbol);

    Optional<Instrument> findBySymbol(String symbol);

    List<Instrument> findByNameContainingIgnoreCaseOrSymbolContainingIgnoreCase(
            String name, String symbol, Sort sort);
}
