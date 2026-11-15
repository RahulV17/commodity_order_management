package com.example.instrument.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.instrument.dto.InstrumentRequest;
import com.example.instrument.dto.InstrumentResponse;
import com.example.instrument.dto.StatusUpdateRequest;
import com.example.instrument.dto.UpdateInstrumentRequest;
import com.example.instrument.service.InstrumentService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/instruments")
@Tag(name = "Instruments", description = "Commodity instrument catalogue")
public class InstrumentController {

    private final InstrumentService instrumentService;

    public InstrumentController(InstrumentService instrumentService) {
        this.instrumentService = instrumentService;
    }

    @GetMapping
    @Operation(summary = "List instruments, optionally filtered by search text or active flag")
    public List<InstrumentResponse> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Boolean active) {
        return instrumentService.findAll(search, active);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get an instrument by business id (cache-aside read)")
    public InstrumentResponse getById(@PathVariable String id) {
        return instrumentService.findById(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Create an instrument (ADMIN only)")
    public InstrumentResponse create(@Valid @RequestBody InstrumentRequest request) {
        return instrumentService.create(request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Update an instrument (ADMIN only)")
    public InstrumentResponse update(@PathVariable String id,
                                     @Valid @RequestBody UpdateInstrumentRequest request) {
        return instrumentService.update(id, request);
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Activate or deactivate an instrument (ADMIN only)")
    public InstrumentResponse updateStatus(@PathVariable String id,
                                           @Valid @RequestBody StatusUpdateRequest request) {
        return instrumentService.updateStatus(id, request.active());
    }
}
