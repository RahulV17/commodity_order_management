package com.example.order.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Declarative REST client. "instrument-service" is resolved by Eureka, so no
 * host or port is hard-coded here. FeignConfig propagates the caller's JWT.
 */
@FeignClient(name = "instrument-service", configuration = FeignConfig.class)
public interface InstrumentClient {

    @GetMapping("/api/instruments/{id}")
    InstrumentDto getById(@PathVariable("id") String id);
}
