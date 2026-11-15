package com.example.order.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import com.example.order.dto.DocumentResponse;
import com.example.order.dto.DownloadedDocument;
import com.example.order.entity.Order;
import com.example.order.entity.OrderDocument;
import com.example.order.exception.InvalidDocumentException;
import com.example.order.exception.OrderAccessDeniedException;
import com.example.order.exception.ResourceNotFoundException;
import com.example.order.repository.OrderDocumentRepository;
import com.example.order.repository.OrderRepository;
import com.example.order.storage.DocumentStorage;

@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private static final long MAX_FILE_SIZE_BYTES = 5L * 1024 * 1024;
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "application/pdf",
            "image/png",
            "image/jpeg",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final OrderRepository orderRepository;
    private final OrderDocumentRepository documentRepository;
    private final DocumentStorage documentStorage;

    public DocumentService(OrderRepository orderRepository,
                           OrderDocumentRepository documentRepository,
                           DocumentStorage documentStorage) {
        this.orderRepository = orderRepository;
        this.documentRepository = documentRepository;
        this.documentStorage = documentStorage;
    }

    @Transactional
    public DocumentResponse upload(Long orderId, MultipartFile file, Authentication authentication) {
        Order order = getOrder(orderId);
        assertOwnerOrAdmin(order, authentication);

        if (file == null || file.isEmpty()) {
            throw new InvalidDocumentException("A non-empty file is required");
        }
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new InvalidDocumentException("The file exceeds the 5 MB limit");
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new InvalidDocumentException(
                    "Unsupported file type. Allowed: PDF, PNG, JPEG, DOCX, XLSX");
        }

        String fileName = safeFileName(file.getOriginalFilename());
        String key = "orders/" + orderId + "/" + UUID.randomUUID() + "-" + fileName;

        try (InputStream inputStream = file.getInputStream()) {
            documentStorage.store(key, inputStream, file.getSize(), contentType);
        } catch (IOException ex) {
            throw new InvalidDocumentException("The uploaded file could not be read");
        }

        OrderDocument document = new OrderDocument();
        document.setOrder(order);
        document.setFileName(fileName);
        document.setContentType(contentType);
        document.setSizeBytes(file.getSize());
        document.setStorageKey(key);
        document.setUploadedBy(authentication.getName());

        OrderDocument saved = documentRepository.save(document);
        log.info("Stored document id={} order={} fileName={} bytes={} backend={}",
                saved.getId(), order.getOrderNumber(), fileName, file.getSize(), documentStorage.backend());
        return DocumentResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<DocumentResponse> list(Long orderId, Authentication authentication) {
        Order order = getOrder(orderId);
        assertOwnerOrAdmin(order, authentication);
        return documentRepository.findByOrderIdOrderByUploadedAtDesc(orderId)
                .stream()
                .map(DocumentResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public DownloadedDocument download(Long orderId, Long documentId, Authentication authentication) {
        Order order = getOrder(orderId);
        assertOwnerOrAdmin(order, authentication);

        OrderDocument document = documentRepository.findByIdAndOrderId(documentId, orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found: " + documentId));
        byte[] content = documentStorage.read(document.getStorageKey());
        return new DownloadedDocument(document.getFileName(), document.getContentType(), content);
    }

    private Order getOrder(Long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));
    }

    private void assertOwnerOrAdmin(Order order, Authentication authentication) {
        boolean admin = authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
        if (!admin && !order.getCreatedBy().equals(authentication.getName())) {
            throw new OrderAccessDeniedException("You can only access documents for your own orders");
        }
    }

    private String safeFileName(String originalFilename) {
        String cleaned = StringUtils.cleanPath(
                StringUtils.hasText(originalFilename) ? originalFilename : "document");
        if (cleaned.contains("..")) {
            throw new InvalidDocumentException("Invalid file name");
        }
        String baseName = Path.of(cleaned).getFileName().toString();
        String safe = baseName.replaceAll("[^A-Za-z0-9._-]", "_");
        return safe.length() > 120 ? safe.substring(safe.length() - 120) : safe;
    }
}
