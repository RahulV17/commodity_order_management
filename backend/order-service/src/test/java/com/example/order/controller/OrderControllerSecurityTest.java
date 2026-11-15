package com.example.order.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import com.example.order.dto.OrderResponse;
import com.example.order.entity.OrderStatus;
import com.example.order.entity.OrderType;
import com.example.order.exception.GlobalExceptionHandler;
import com.example.order.security.JwtAuthenticationFilter;
import com.example.order.security.JwtService;
import com.example.order.security.RestAccessDeniedHandler;
import com.example.order.security.RestAuthenticationEntryPoint;
import com.example.order.security.SecurityConfig;
import com.example.order.service.DashboardService;
import com.example.order.service.OrderService;

@WebMvcTest(controllers = OrderController.class)
@Import({
        SecurityConfig.class,
        JwtAuthenticationFilter.class,
        RestAuthenticationEntryPoint.class,
        RestAccessDeniedHandler.class,
        GlobalExceptionHandler.class
})
class OrderControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private OrderService orderService;

    @MockBean
    private DashboardService dashboardService;

    @MockBean
    private JwtService jwtService;

    @Test
    void protectedEndpointReturns401WithoutToken() throws Exception {
        mockMvc.perform(get("/api/orders"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "operator", roles = "OPERATIONS")
    void operationsUserCannotApproveOrder() throws Exception {
        mockMvc.perform(patch("/api/orders/1/approve"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void adminCanApproveOrder() throws Exception {
        when(orderService.approve(eq(1L), any())).thenReturn(new OrderResponse(
                1L, "ORD-1", "GOLD-001", "GOLD", "Gold", OrderType.BUY,
                new BigDecimal("10"), new BigDecimal("15000"), OrderStatus.APPROVED,
                null, null, "operator", Instant.now(), Instant.now()));

        mockMvc.perform(patch("/api/orders/1/approve"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    @WithMockUser(username = "operator", roles = "OPERATIONS")
    void createRejectsInvalidPayload() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"instrumentId\":\"\",\"quantity\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"));
    }
}
