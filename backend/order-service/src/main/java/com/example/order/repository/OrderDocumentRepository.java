package com.example.order.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.order.entity.OrderDocument;

public interface OrderDocumentRepository extends JpaRepository<OrderDocument, Long> {

    List<OrderDocument> findByOrderIdOrderByUploadedAtDesc(Long orderId);

    Optional<OrderDocument> findByIdAndOrderId(Long id, Long orderId);
}
