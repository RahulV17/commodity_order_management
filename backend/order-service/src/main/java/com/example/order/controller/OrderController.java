package com.example.order.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.order.dto.CreateOrderRequest;
import com.example.order.dto.DashboardStats;
import com.example.order.dto.OrderResponse;
import com.example.order.dto.PageResponse;
import com.example.order.dto.RejectOrderRequest;
import com.example.order.entity.OrderStatus;
import com.example.order.service.DashboardService;
import com.example.order.service.OrderService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/orders")
@Tag(name = "Orders", description = "Order creation, tracking and approval workflow")
public class OrderController {

    private final OrderService orderService;
    private final DashboardService dashboardService;

    public OrderController(OrderService orderService, DashboardService dashboardService) {
        this.orderService = orderService;
        this.dashboardService = dashboardService;
    }

    @GetMapping
    @Operation(summary = "List orders with pagination, sorting and optional filters")
    public PageResponse<OrderResponse> list(
            @RequestParam(required = false) String instrumentId,
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "createdAt,desc") String sort,
            Authentication authentication) {
        return orderService.findAll(instrumentId, status, search, page, size, sort, authentication);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create an order after validating the instrument through Feign")
    public OrderResponse create(@Valid @RequestBody CreateOrderRequest request,
                                Authentication authentication) {
        return orderService.create(request, authentication);
    }

    @GetMapping("/dashboard")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Dashboard statistics calculated by the backend (ADMIN only)")
    public DashboardStats dashboard() {
        return dashboardService.getStats();
    }

    @GetMapping("/{id}")
    @Operation(summary = "View an order. Operations users can only read their own orders.")
    public OrderResponse getById(@PathVariable Long id, Authentication authentication) {
        return orderService.findById(id, authentication);
    }

    /**
     * Creation produces a NEW draft. This explicit submit step is what moves it
     * to SUBMITTED, where an administrator can approve or reject it.
     */
    @PatchMapping("/{id}/submit")
    @Operation(summary = "Submit a NEW order for approval")
    public OrderResponse submit(@PathVariable Long id, Authentication authentication) {
        return orderService.submit(id, authentication);
    }

    @PatchMapping("/{id}/approve")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Approve a SUBMITTED order (ADMIN only)")
    public OrderResponse approve(@PathVariable Long id, Authentication authentication) {
        return orderService.approve(id, authentication);
    }

    @PatchMapping("/{id}/reject")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Reject a SUBMITTED order with a reason (ADMIN only)")
    public OrderResponse reject(@PathVariable Long id,
                                @Valid @RequestBody RejectOrderRequest request,
                                Authentication authentication) {
        return orderService.reject(id, request.reason(), authentication);
    }

    @PatchMapping("/{id}/cancel")
    @Operation(summary = "Cancel a NEW or SUBMITTED order. Operations users can cancel only their own.")
    public OrderResponse cancel(@PathVariable Long id, Authentication authentication) {
        return orderService.cancel(id, authentication);
    }
}
