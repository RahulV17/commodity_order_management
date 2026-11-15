package com.example.order.controller;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.example.order.dto.DocumentResponse;
import com.example.order.dto.DownloadedDocument;
import com.example.order.service.DocumentService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/orders/{orderId}/documents")
@Tag(name = "Order documents", description = "Optional supporting-document upload and download")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Upload a PDF/PNG/JPEG/DOCX/XLSX file (max 5 MB) for an order")
    public DocumentResponse upload(@PathVariable Long orderId,
                                   @RequestPart("file") MultipartFile file,
                                   Authentication authentication) {
        return documentService.upload(orderId, file, authentication);
    }

    @GetMapping
    @Operation(summary = "List documents attached to an order")
    public List<DocumentResponse> list(@PathVariable Long orderId, Authentication authentication) {
        return documentService.list(orderId, authentication);
    }

    @GetMapping("/{documentId}/download")
    @Operation(summary = "Download a document through a controlled, authenticated endpoint")
    public ResponseEntity<byte[]> download(@PathVariable Long orderId,
                                           @PathVariable Long documentId,
                                           Authentication authentication) {
        DownloadedDocument document = documentService.download(orderId, documentId, authentication);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(document.fileName(), StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .contentType(MediaType.parseMediaType(document.contentType()))
                .contentLength(document.content().length)
                .body(document.content());
    }
}
