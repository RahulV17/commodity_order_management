package com.example.order.exception;

public class InstrumentInactiveException extends RuntimeException {

    public InstrumentInactiveException(String instrumentId) {
        super("Instrument is not active and cannot be traded: " + instrumentId);
    }
}
