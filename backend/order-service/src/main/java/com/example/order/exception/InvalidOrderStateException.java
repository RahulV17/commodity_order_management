package com.example.order.exception;

import com.example.order.entity.OrderStatus;

public class InvalidOrderStateException extends RuntimeException {

    public InvalidOrderStateException(String orderNumber, OrderStatus current, OrderStatus target) {
        super("Order " + orderNumber + " cannot move from " + current + " to " + target);
    }
}
