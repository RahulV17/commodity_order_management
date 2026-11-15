package com.example.order.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.example.order.exception.InvalidOrderStateException;

class OrderStatusTest {

    @Test
    void newCanOnlyBeSubmittedOrCancelled() {
        assertThat(OrderStatus.NEW.canTransitionTo(OrderStatus.SUBMITTED)).isTrue();
        assertThat(OrderStatus.NEW.canTransitionTo(OrderStatus.CANCELLED)).isTrue();
        assertThat(OrderStatus.NEW.canTransitionTo(OrderStatus.APPROVED)).isFalse();
        assertThat(OrderStatus.NEW.canTransitionTo(OrderStatus.REJECTED)).isFalse();
    }

    @Test
    void submittedCanBeApprovedRejectedOrCancelled() {
        assertThat(OrderStatus.SUBMITTED.canTransitionTo(OrderStatus.APPROVED)).isTrue();
        assertThat(OrderStatus.SUBMITTED.canTransitionTo(OrderStatus.REJECTED)).isTrue();
        assertThat(OrderStatus.SUBMITTED.canTransitionTo(OrderStatus.CANCELLED)).isTrue();
    }

    @Test
    void terminalStatusesCannotTransitionAgain() {
        for (OrderStatus status : new OrderStatus[]{
                OrderStatus.APPROVED, OrderStatus.REJECTED, OrderStatus.CANCELLED}) {
            for (OrderStatus target : OrderStatus.values()) {
                assertThat(status.canTransitionTo(target)).isFalse();
            }
        }
    }

    @Test
    void entityRejectsInvalidTransition() {
        Order order = new Order();
        order.setOrderNumber("ORD-1");
        order.setStatus(OrderStatus.NEW);

        assertThatThrownBy(order::approve).isInstanceOf(InvalidOrderStateException.class);
    }

    @Test
    void rejectionRequiresReason() {
        Order order = new Order();
        order.setOrderNumber("ORD-1");
        order.setStatus(OrderStatus.SUBMITTED);

        assertThatThrownBy(() -> order.reject("  ")).isInstanceOf(IllegalArgumentException.class);
    }
}
