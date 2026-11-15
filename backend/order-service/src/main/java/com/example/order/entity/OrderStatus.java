package com.example.order.entity;

/**
 * Explicit state machine for the order lifecycle.
 *
 * NEW -> SUBMITTED -> APPROVED
 *                  -> REJECTED
 * NEW/SUBMITTED -> CANCELLED
 *
 * Terminal states cannot be processed again.
 */
public enum OrderStatus {
    NEW,
    SUBMITTED,
    APPROVED,
    REJECTED,
    CANCELLED;

    public boolean canTransitionTo(OrderStatus target) {
        return switch (this) {
            case NEW -> target == SUBMITTED || target == CANCELLED;
            case SUBMITTED -> target == APPROVED || target == REJECTED || target == CANCELLED;
            case APPROVED, REJECTED, CANCELLED -> false;
        };
    }
}
