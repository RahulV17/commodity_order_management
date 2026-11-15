package com.example.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.util.ReflectionTestUtils;

import com.example.order.client.InstrumentClient;
import com.example.order.client.InstrumentDto;
import com.example.order.dto.CreateOrderRequest;
import com.example.order.dto.OrderResponse;
import com.example.order.entity.Order;
import com.example.order.entity.OrderStatus;
import com.example.order.entity.OrderType;
import com.example.order.exception.InstrumentInactiveException;
import com.example.order.exception.InstrumentUnavailableException;
import com.example.order.exception.InvalidOrderStateException;
import com.example.order.exception.OrderAccessDeniedException;
import com.example.order.exception.ResourceNotFoundException;
import com.example.order.kafka.OrderEventProducer;
import com.example.order.mapper.OrderMapper;
import com.example.order.repository.OrderRepository;
import com.example.order.util.OrderNumberGenerator;

import feign.FeignException;
import feign.Request;
import feign.Response;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private InstrumentClient instrumentClient;

    @Mock
    private OrderEventProducer eventProducer;

    @Mock
    private OrderNumberGenerator orderNumberGenerator;

    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderService = new OrderService(
                orderRepository, instrumentClient, eventProducer, new OrderMapper(), orderNumberGenerator);
    }

    @Test
    void createPersistsNewOrderAndPublishesEvent() {
        when(instrumentClient.getById("GOLD-001"))
                .thenReturn(activeInstrument());
        when(orderNumberGenerator.next()).thenReturn("ORD-TEST0001");
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
            Order saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 1L);
            return saved;
        });

        OrderResponse response = orderService.create(
                new CreateOrderRequest("GOLD-001", OrderType.BUY,
                        new BigDecimal("10"), new BigDecimal("15000"), "Sample order"),
                authentication("operator", "OPERATIONS"));

        assertThat(response.orderNumber()).isEqualTo("ORD-TEST0001");
        assertThat(response.status()).isEqualTo(OrderStatus.NEW);
        assertThat(response.instrumentSymbol()).isEqualTo("GOLD");
        assertThat(response.createdBy()).isEqualTo("operator");
        verify(eventProducer).publishOrderCreated(any(Order.class));
    }

    @Test
    void createRejectsInactiveInstrument() {
        InstrumentDto instrument = activeInstrument();
        InstrumentDto inactive = new InstrumentDto(instrument.id(), instrument.symbol(), instrument.name(),
                instrument.category(), instrument.indicativePrice(), false, 0);
        when(instrumentClient.getById("GOLD-001")).thenReturn(inactive);

        assertThatThrownBy(() -> orderService.create(
                new CreateOrderRequest("GOLD-001", OrderType.BUY,
                        new BigDecimal("10"), new BigDecimal("15000"), null),
                authentication("operator", "OPERATIONS")))
                .isInstanceOf(InstrumentInactiveException.class);

        verify(orderRepository, never()).save(any(Order.class));
        verify(eventProducer, never()).publishOrderCreated(any(Order.class));
    }

    @Test
    void createMapsFeignNotFoundToResourceNotFound() {
        when(instrumentClient.getById("UNKNOWN")).thenThrow(feignException(404));

        assertThatThrownBy(() -> orderService.create(
                new CreateOrderRequest("UNKNOWN", OrderType.BUY,
                        new BigDecimal("1"), new BigDecimal("10"), null),
                authentication("operator", "OPERATIONS")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void createMapsInstrumentServiceFailureToServiceUnavailable() {
        when(instrumentClient.getById("GOLD-001")).thenThrow(feignException(503));

        assertThatThrownBy(() -> orderService.create(
                new CreateOrderRequest("GOLD-001", OrderType.BUY,
                        new BigDecimal("1"), new BigDecimal("10"), null),
                authentication("operator", "OPERATIONS")))
                .isInstanceOf(InstrumentUnavailableException.class);
    }

    @Test
    void approveMovesSubmittedOrderToApproved() {
        Order order = order(7L, OrderStatus.SUBMITTED, "operator");
        when(orderRepository.findById(7L)).thenReturn(Optional.of(order));
        when(orderRepository.save(order)).thenReturn(order);

        OrderResponse response = orderService.approve(7L, authentication("admin", "ADMIN"));

        assertThat(response.status()).isEqualTo(OrderStatus.APPROVED);
    }

    @Test
    void approveRejectsInvalidTransitionFromApproved() {
        Order order = order(8L, OrderStatus.APPROVED, "operator");
        when(orderRepository.findById(8L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.approve(8L, authentication("admin", "ADMIN")))
                .isInstanceOf(InvalidOrderStateException.class);

        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    void submitMovesNewOrderToSubmitted() {
        Order order = order(9L, OrderStatus.NEW, "operator");
        when(orderRepository.findById(9L)).thenReturn(Optional.of(order));
        when(orderRepository.save(order)).thenReturn(order);

        OrderResponse response = orderService.submit(9L, authentication("operator", "OPERATIONS"));

        assertThat(response.status()).isEqualTo(OrderStatus.SUBMITTED);
    }

    @Test
    void operationsUserCannotCancelAnotherUsersOrder() {
        Order order = order(10L, OrderStatus.SUBMITTED, "someone-else");
        when(orderRepository.findById(10L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.cancel(10L, authentication("operator", "OPERATIONS")))
                .isInstanceOf(OrderAccessDeniedException.class);

        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    void adminCanCancelAnyOrder() {
        Order order = order(11L, OrderStatus.SUBMITTED, "someone-else");
        when(orderRepository.findById(11L)).thenReturn(Optional.of(order));
        when(orderRepository.save(order)).thenReturn(order);

        OrderResponse response = orderService.cancel(11L, authentication("admin", "ADMIN"));

        assertThat(response.status()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void findByIdHidesAnotherUsersOrderFromOperationsUser() {
        Order order = order(12L, OrderStatus.NEW, "another-user");
        when(orderRepository.findById(12L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.findById(12L, authentication("operator", "OPERATIONS")))
                .isInstanceOf(OrderAccessDeniedException.class);
    }

    private InstrumentDto activeInstrument() {
        return new InstrumentDto("GOLD-001", "GOLD", "Gold", "PRECIOUS_METAL",
                new BigDecimal("15000"), true, 0);
    }

    private Order order(Long id, OrderStatus status, String createdBy) {
        Order order = new Order();
        order.setId(id);
        order.setOrderNumber("ORD-" + id);
        order.setInstrumentId("GOLD-001");
        order.setInstrumentSymbol("GOLD");
        order.setInstrumentName("Gold");
        order.setOrderType(OrderType.BUY);
        order.setQuantity(new BigDecimal("10"));
        order.setPrice(new BigDecimal("15000"));
        order.setStatus(status);
        order.setCreatedBy(createdBy);
        return order;
    }

    private Authentication authentication(String username, String role) {
        return new UsernamePasswordAuthenticationToken(username, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    private FeignException feignException(int status) {
        Request request = Request.create(
                Request.HttpMethod.GET,
                "/api/instruments/GOLD-001",
                Map.of(),
                new byte[0],
                StandardCharsets.UTF_8,
                null);
        Response response = Response.builder()
                .status(status)
                .reason("simulated")
                .request(request)
                .headers(Map.of())
                .build();
        return FeignException.errorStatus("InstrumentClient#getById", response);
    }
}
