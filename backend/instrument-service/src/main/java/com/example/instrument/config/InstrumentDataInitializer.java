package com.example.instrument.config;

import java.math.BigDecimal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.example.instrument.document.Instrument;
import com.example.instrument.document.InstrumentCategory;
import com.example.instrument.repository.InstrumentRepository;

/**
 * Seeds the demo catalogue once. The data is intentionally minimal and is not
 * live market data.
 */
@Component
public class InstrumentDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(InstrumentDataInitializer.class);

    private final InstrumentRepository instrumentRepository;

    public InstrumentDataInitializer(InstrumentRepository instrumentRepository) {
        this.instrumentRepository = instrumentRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (instrumentRepository.count() > 0) {
            log.info("Instrument seed skipped: collection already contains data");
            return;
        }

        instrumentRepository.save(build("GOLD-001", "GOLD", "Gold",
                InstrumentCategory.PRECIOUS_METAL, "15000.0000"));
        instrumentRepository.save(build("SILVER-001", "SILVER", "Silver",
                InstrumentCategory.PRECIOUS_METAL, "95000.0000"));
        instrumentRepository.save(build("CRUDE-001", "CRUDEOIL", "Crude Oil",
                InstrumentCategory.ENERGY, "6500.0000"));
        instrumentRepository.save(build("GAS-001", "NATGAS", "Natural Gas",
                InstrumentCategory.ENERGY, "320.0000"));

        log.info("Seeded {} demo instruments", instrumentRepository.count());
    }

    private Instrument build(String id, String symbol, String name,
                             InstrumentCategory category, String price) {
        Instrument instrument = new Instrument();
        instrument.setId(id);
        instrument.setSymbol(symbol);
        instrument.setName(name);
        instrument.setCategory(category);
        instrument.setIndicativePrice(new BigDecimal(price));
        instrument.setActive(true);
        return instrument;
    }
}
