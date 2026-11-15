package com.example.order.exception;

public class InstrumentUnavailableException extends RuntimeException {

    public InstrumentUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
