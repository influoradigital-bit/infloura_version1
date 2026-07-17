package com.influora.web;

import com.influora.common.ApiResponse;
import com.influora.security.AuthPrincipal;
import com.influora.service.CreatorDiscoveryService;
import com.influora.web.dto.creator.CreatorDtos.CreatorResponse;
import com.influora.web.dto.creator.CreatorDtos.InviteRequest;
import com.influora.web.dto.creator.CreatorDtos.InviteResponse;
import com.influora.web.dto.creator.CreatorDtos.SaveRequest;
import com.influora.web.dto.creator.CreatorDtos.SaveResponse;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/creators")
public class CreatorController {

    private final CreatorDiscoveryService creatorDiscoveryService;

    public CreatorController(CreatorDiscoveryService creatorDiscoveryService) {
        this.creatorDiscoveryService = creatorDiscoveryService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<java.util.List<CreatorResponse>>> search(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String platforms,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String verticals,
            @RequestParam(required = false) Long minFollowers,
            @RequestParam(required = false) Long maxFollowers,
            @RequestParam(required = false) BigDecimal minRate,
            @RequestParam(required = false) BigDecimal maxRate,
            @RequestParam(required = false) BigDecimal minEngagementRate,
            @RequestParam(required = false) BigDecimal maxEngagementRate,
            @RequestParam(required = false) Boolean isVerified,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "followers") String sortBy) {
        var result =
                creatorDiscoveryService.search(
                        principal,
                        q,
                        platforms,
                        city,
                        verticals,
                        minFollowers,
                        maxFollowers,
                        minRate,
                        maxRate,
                        minEngagementRate,
                        maxEngagementRate,
                        isVerified,
                        page,
                        limit,
                        sortBy);
        return ResponseEntity.ok(ApiResponse.ok(result.items(), result.meta()));
    }

    @GetMapping("/{creatorId}")
    public ResponseEntity<ApiResponse<CreatorResponse>> get(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable String creatorId) {
        return ResponseEntity.ok(ApiResponse.ok(creatorDiscoveryService.get(principal, creatorId)));
    }

    @PostMapping("/{creatorId}/save")
    public ResponseEntity<ApiResponse<SaveResponse>> save(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String creatorId,
            @Valid @RequestBody SaveRequest body) {
        return ResponseEntity.ok(
                ApiResponse.ok(
                        creatorDiscoveryService.toggleSaved(principal, creatorId, body.saved())));
    }

    @PostMapping("/{creatorId}/invite")
    public ResponseEntity<ApiResponse<InviteResponse>> invite(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String creatorId,
            @Valid @RequestBody InviteRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(
                        ApiResponse.ok(
                                creatorDiscoveryService.invite(
                                        principal, creatorId, body.campaignId(), body.message())));
    }
}
