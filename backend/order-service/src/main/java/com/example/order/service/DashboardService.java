package com.example.order.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.order.dto.DashboardStats;
import com.example.order.dto.OrderResponse;
import com.example.order.entity.OrderStatus;
import com.example.order.mapper.OrderMapper;
import com.example.order.repository.OrderRepository;

@Service
public class DashboardService {

    private final OrderRepository orderRepository;
    private final OrderMapper orderMapper;

    public DashboardService(OrderRepository orderRepository, OrderMapper orderMapper) {
        this.orderRepository = orderRepository;
        this.orderMapper = orderMapper;
    }

    @Transactional(readOnly = true)
    public DashboardStats getStats() {
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (OrderStatus status : OrderStatus.values()) {
            byStatus.put(status.name(), 0L);
        }

        List<Object[]> grouped = orderRepository.countGroupedByStatus();
        long total = 0L;
        for (Object[] row : grouped) {
            OrderStatus status = (OrderStatus) row[0];
            long count = ((Number) row[1]).longValue();
            byStatus.put(status.name(), count);
            total += count;
        }

        List<OrderResponse> recentOrders = orderRepository.findTop5ByOrderByCreatedAtDesc()
                .stream()
                .map(orderMapper::toResponse)
                .toList();

        return new DashboardStats(
                total,
                byStatus.get(OrderStatus.SUBMITTED.name()),
                byStatus.get(OrderStatus.APPROVED.name()),
                byStatus.get(OrderStatus.REJECTED.name()),
                byStatus.get(OrderStatus.CANCELLED.name()),
                byStatus.get(OrderStatus.NEW.name()),
                byStatus,
                recentOrders);
    }
}
