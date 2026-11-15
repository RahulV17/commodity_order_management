package com.example.order.dto;

import java.util.List;
import java.util.Map;

public record DashboardStats(
        long totalOrders,
        long pendingOrders,
        long approvedOrders,
        long rejectedOrders,
        long cancelledOrders,
        long newOrders,
        Map<String, Long> ordersByStatus,
        List<OrderResponse> recentOrders) {
}
