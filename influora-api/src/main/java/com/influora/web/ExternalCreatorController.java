package com.influora.web;

import com.influora.common.ApiResponse;
import com.influora.common.PageMeta;
import com.influora.security.AuthPrincipal;
import com.influora.service.ExternalCreatorService;
import com.influora.service.ExternalCreatorService.PageResult;
import com.influora.web.dto.creator.ExternalCreatorDtos.ConnectRequest;
import com.influora.web.dto.creator.ExternalCreatorDtos.ConnectionRequestResponse;
import com.influora.web.dto.creator.ExternalCreatorDtos.ExternalCreatorResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Brand-facing Meta-sourced creator discovery + "Connect this creator" (T-CREATORCONNECT-0902).
 * Brand principal required throughout — {@code BrandContextService.requireBrandWorkspace}.
 */
@RestController
@RequestMapping("/creators/external")
public class ExternalCreatorController {

    private final ExternalCreatorService externalCreatorService;

    public ExternalCreatorController(ExternalCreatorService externalCreatorService) {
        this.externalCreatorService = externalCreatorService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<ExternalCreatorResponse>>> list(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Long minFollowers,
            @RequestParam(required = false) Long maxFollowers,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int limit) {
        PageResult<ExternalCreatorResponse> result =
                externalCreatorService.list(principal, q, minFollowers, maxFollowers, page, limit);
        return ResponseEntity.ok(
                ApiResponse.ok(
                        result.items(), new PageMeta(page, limit, result.total(), result.hasMore())));
    }

    @GetMapping("/lookup")
    public ResponseEntity<ApiResponse<ExternalCreatorResponse>> lookup(
            @AuthenticationPrincipal AuthPrincipal principal, @RequestParam String username) {
        return ResponseEntity.ok(ApiResponse.ok(externalCreatorService.lookup(principal, username)));
    }

    @PostMapping("/{id}/connect")
    public ResponseEntity<ApiResponse<ConnectionRequestResponse>> connect(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String id,
            @Valid @RequestBody(required = false) ConnectRequest body) {
        return ResponseEntity.ok(
                ApiResponse.ok(
                        externalCreatorService.connect(principal, id, body != null ? body.message() : null)));
    }

    @GetMapping("/connection-requests")
    public ResponseEntity<ApiResponse<List<ConnectionRequestResponse>>> connectionRequests(
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(externalCreatorService.connectionRequests(principal)));
    }
}
