package com.example.order.util;

import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Component;

@Component
public class OrderNumberGenerator {

    /**
     * Human-readable, unique order number. UUID randomness avoids collisions
     * across instances; the database unique constraint is the final guard.
     */
    public String next() {
        return "ORD-" + UUID.randomUUID().toString()
                .replace("-", "")
                .substring(0, 8)
                .toUpperCase(Locale.ROOT);
    }
}
