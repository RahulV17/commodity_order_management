package com.example.instrument.exception;

public class InstrumentNotFoundException extends RuntimeException {

    public InstrumentNotFoundException(String id) {
        super("Instrument not found: " + id);
    }
}
