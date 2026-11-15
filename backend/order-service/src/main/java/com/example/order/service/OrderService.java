package com.example.order.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.example.order.client.InstrumentClient;
import com.example.order.client.InstrumentDto;
import com.example.order.dto.CreateOrderRequest;
import com.example.order.dto.OrderResponse;
import com.example.order.dto.PageResponse;
import com.example.order.entity.Order;
import com.example.order.entity.OrderStatus;
import com.example.order.exception.InstrumentInactiveException;
import com.example.order.exception.InstrumentUnavailableException;
import com.example.order.exception.OrderAccessDeniedException;
import com.example.order.exception.ResourceNotFoundException;
import com.example.order.kafka.OrderEventProducer;
import com.example.order.mapper.OrderMapper;
import com.example.order.repository.OrderRepository;
import com.example.order.util.OrderNumberGenerator;

import feign.FeignException;
import jakarta.persistence.criteria.Predicate;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    private static final Set<String> SORTABLE_FIELDS =
            Set.of("createdAt", "updatedAt", "orderNumber", "price", "quantity", "status");

    private final OrderRepository orderRepository;
    private final InstrumentClient instrumentClient;
    private final OrderEventProducer eventProducer;
    private final OrderMapper orderMapper;
    private final OrderNumberGenerator orderNumberGenerator;

    public OrderService(OrderRepository orderRepository,
                        InstrumentClient instrumentClient,
                        OrderEventProducer eventProducer,
                        OrderMapper orderMapper,
                        OrderNumberGenerator orderNumberGenerator) {
        this.orderRepository = orderRepository;
        this.instrumentClient = instrumentClient;
        this.eventProducer = eventProducer;
        this.orderMapper = orderMapper;
        this.orderNumberGenerator = orderNumberGenerator;
    }

    @Transactional(readOnly = true)
    public PageResponse<OrderResponse> findAll(String instrumentId,
                                               OrderStatus status,
                                               String search,
                                               int page,
                                               int size,
                                               String sort,
                                               Authentication authentication) {
        boolean admin = isAdmin(authentication);
        String username = authentication.getName();

        Specification<Order> specification = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (StringUtils.hasText(instrumentId)) {
                predicates.add(cb.equal(root.get("instrumentId"), instrumentId.trim()));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (StringUtils.hasText(search)) {
                String like = "%" + search.trim().toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("orderNumber")), like),
                        cb.like(cb.lower(root.get("instrumentSymbol")), like),
                        cb.like(cb.lower(root.get("instrumentName")), like)));
            }
            // Operations users only see their own orders. The rule is enforced
            // in the query, not only in the Angular UI.
            if (!admin) {
                predicates.add(cb.equal(root.get("createdBy"), username));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        Pageable pageable = PageRequest.of(safePage, safeSize, parseSort(sort));
        Page<Order> result = orderRepository.findAll(specification, pageable);
        return PageResponse.from(result.map(orderMapper::toResponse));
    }

    @Transactional(readOnly = true)
    public OrderResponse findById(Long id, Authentication authentication) {
        Order order = getOrder(id);
        assertOwnerOrAdmin(order, authentication, "You can only view your own orders");
        return orderMapper.toResponse(order);
    }

    @Transactional
    public OrderResponse create(CreateOrderRequest request, Authentication authentication) {
        InstrumentDto instrument = loadInstrument(request.instrumentId());
        if (!instrument.active()) {
            throw new InstrumentInactiveException(request.instrumentId());
        }

        Order order = new Order();
        order.setOrderNumber(orderNumberGenerator.next());
        order.setInstrumentId(instrument.id());
        order.setInstrumentSymbol(instrument.symbol());
        order.setInstrumentName(instrument.name());
        order.setOrderType(request.orderType());
        order.setQuantity(request.quantity());
        order.setPrice(request.price());
        order.setRemarks(StringUtils.hasText(request.remarks()) ? request.remarks().trim() : null);
        order.setCreatedBy(authentication.getName());
        order.setStatus(OrderStatus.NEW);

        Order saved = orderRepository.save(order);
        eventProducer.publishOrderCreated(saved);

        log.info("Created order number={} instrument={} type={} quantity={} status={} createdBy={}",
                saved.getOrderNumber(), saved.getInstrumentId(), saved.getOrderType(),
                saved.getQuantity(), saved.getStatus(), saved.getCreatedBy());
        return orderMapper.toResponse(saved);
    }

    @Transactional
    public OrderResponse submit(Long id, Authentication authentication) {
        Order order = getOrder(id);
        assertOwnerOrAdmin(order, authentication, "You can only submit your own orders");
        order.submit();
        Order saved = orderRepository.save(order);
        log.info("Order {} submitted by {}", saved.getOrderNumber(), authentication.getName());
        return orderMapper.toResponse(saved);
    }

    @Transactional
    public OrderResponse approve(Long id, Authentication authentication) {
        Order order = getOrder(id);
        order.approve();
        Order saved = orderRepository.save(order);
        log.info("Order {} approved by {}", saved.getOrderNumber(), authentication.getName());
        return orderMapper.toResponse(saved);
    }

    @Transactional
    public OrderResponse reject(Long id, String reason, Authentication authentication) {
        Order order = getOrder(id);
        order.reject(reason);
        Order saved = orderRepository.save(order);
        log.info("Order {} rejected by {} reason={}", saved.getOrderNumber(), authentication.getName(), reason);
        return orderMapper.toResponse(saved);
    }

    @Transactional
    public OrderResponse cancel(Long id, Authentication authentication) {
        Order order = getOrder(id);
        assertOwnerOrAdmin(order, authentication, "You can only cancel your own orders");
        order.cancel();
        Order saved = orderRepository.save(order);
        log.info("Order {} cancelled by {}", saved.getOrderNumber(), authentication.getName());
        return orderMapper.toResponse(saved);
    }

    /**
     * Synchronous inter-service validation through Feign. The instrument
     * service is the authority on whether an instrument exists and is active.
     */
    private InstrumentDto loadInstrument(String instrumentId) {
        try {
            return instrumentClient.getById(instrumentId);
        } catch (FeignException ex) {
            log.warn("Instrument service call failed for {} with status {}: {}",
                    instrumentId, ex.status(), ex.getMessage());
            if (ex.status() == 404) {
                throw new ResourceNotFoundException("Instrument not found: " + instrumentId);
            }
            if (ex.status() == 401 || ex.status() == 403) {
                throw new InstrumentUnavailableException(
                        "Instrument service rejected the request. Please sign in again.", ex);
            }
            throw new InstrumentUnavailableException(
                    "Instrument service is unavailable. Please retry shortly.", ex);
        } catch (Exception ex) {
            throw new InstrumentUnavailableException(
                    "Instrument service is unavailable. Please retry shortly.", ex);
        }
    }

    private Order getOrder(Long id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + id));
    }

    private void assertOwnerOrAdmin(Order order, Authentication authentication, String message) {
        if (!isAdmin(authentication) && !order.getCreatedBy().equals(authentication.getName())) {
            throw new OrderAccessDeniedException(message);
        }
    }

    private boolean isAdmin(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
    }

    private Sort parseSort(String sort) {
        String field = "createdAt";
        Sort.Direction direction = Sort.Direction.DESC;

        if (StringUtils.hasText(sort)) {
            String[] parts = sort.split(",");
            if (parts.length > 0 && SORTABLE_FIELDS.contains(parts[0].trim())) {
                field = parts[0].trim();
            }
            if (parts.length > 1 && "asc".equalsIgnoreCase(parts[1].trim())) {
                direction = Sort.Direction.ASC;
            }
        }
        return Sort.by(direction, field);
    }
}
