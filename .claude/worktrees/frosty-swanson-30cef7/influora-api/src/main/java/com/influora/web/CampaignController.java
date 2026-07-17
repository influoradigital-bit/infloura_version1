package com.influora.web;

import com.influora.common.ApiResponse;
import com.influora.security.AuthPrincipal;
import com.influora.service.CampaignService;
import com.influora.web.dto.campaign.CampaignDtos.CampaignPatchRequest;
import com.influora.web.dto.campaign.CampaignDtos.CampaignResponse;
import com.influora.web.dto.campaign.CampaignDtos.CampaignWriteRequest;
import com.influora.web.dto.campaign.CampaignDtos.DeleteResponse;
import com.influora.web.dto.campaign.CampaignDtos.DuplicateResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/campaigns")
public class CampaignController {

    private final CampaignService campaignService;

    public CampaignController(CampaignService campaignService) {
        this.campaignService = campaignService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<java.util.List<CampaignResponse>>> list(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortOrder) {
        var result = campaignService.list(principal, page, limit, status, search, sortBy, sortOrder);
        return ResponseEntity.ok(ApiResponse.ok(result.items(), result.meta()));
    }

    @GetMapping("/{campaignId}")
    public ResponseEntity<ApiResponse<CampaignResponse>> get(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable String campaignId) {
        return ResponseEntity.ok(ApiResponse.ok(campaignService.get(principal, campaignId)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<CampaignResponse>> create(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody CampaignWriteRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(campaignService.create(principal, body)));
    }

    @PatchMapping("/{campaignId}")
    public ResponseEntity<ApiResponse<CampaignResponse>> update(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String campaignId,
            @RequestBody CampaignPatchRequest body) {
        return ResponseEntity.ok(ApiResponse.ok(campaignService.update(principal, campaignId, body)));
    }

    @DeleteMapping("/{campaignId}")
    public ResponseEntity<ApiResponse<DeleteResponse>> delete(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable String campaignId) {
        return ResponseEntity.ok(ApiResponse.ok(campaignService.delete(principal, campaignId)));
    }

    @PostMapping("/{campaignId}/duplicate")
    public ResponseEntity<ApiResponse<DuplicateResponse>> duplicate(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable String campaignId) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(campaignService.duplicate(principal, campaignId)));
    }
}
