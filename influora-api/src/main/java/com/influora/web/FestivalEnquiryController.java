package com.influora.web;

import com.influora.common.ApiResponse;
import com.influora.service.FestivalEnquiryService;
import com.influora.web.dto.FestivalEnquiryDtos.SubmitEnquiryRequest;
import com.influora.web.dto.FestivalEnquiryDtos.SubmitEnquiryResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * PUBLIC enquiry intake for the Festival Box landing page (T-FESTIVALBOX-0905). Full path
 * {@code POST /api/v1/festival-enquiries} given {@code server.servlet.context-path=/api/v1}.
 *
 * <p>Unauthenticated by necessity: the submitter is a brand or creator who has no Influora account
 * yet — that is the entire point of the page. The corresponding {@code permitAll} entry in
 * {@code SecurityConfig} names this exact verb+path (never a {@code /festival-**} wildcard) so a
 * future festival endpoint cannot inherit public access by accident, following the same discipline
 * the Meta callback routes are registered with.
 *
 * <p>Returns the standard {@link ApiResponse} envelope, matching every other non-admin controller.
 * All abuse controls live in {@link FestivalEnquiryService}, not here.
 */
@RestController
public class FestivalEnquiryController {

    private final FestivalEnquiryService festivalEnquiryService;

    public FestivalEnquiryController(FestivalEnquiryService festivalEnquiryService) {
        this.festivalEnquiryService = festivalEnquiryService;
    }

    /**
     * Store one enquiry. {@code httpRequest} is injected for the client address and user agent only
     * — every value that ends up in the row comes from the validated body, so a caller cannot use a
     * header to influence what is persisted about them.
     */
    @PostMapping("/festival-enquiries")
    public ResponseEntity<ApiResponse<SubmitEnquiryResponse>> submit(
            @Valid @RequestBody SubmitEnquiryRequest body, HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.ok(festivalEnquiryService.submit(body, httpRequest)));
    }
}
