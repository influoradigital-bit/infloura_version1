package com.influora.web;

import com.influora.common.ApiResponse;
import com.influora.security.AuthPrincipal;
import com.influora.service.CreatorCampaignService;
import com.influora.web.dto.creatorcampaign.CreatorCampaignDtos.ApplyRequest;
import com.influora.web.dto.creatorcampaign.CreatorCampaignDtos.ApplyResponse;
import com.influora.web.dto.creatorcampaign.CreatorCampaignDtos.CreatorCampaignDetailResponse;
import com.influora.web.dto.creatorcampaign.CreatorCampaignDtos.CreatorCampaignListItem;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.List;
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

/**
 * Task #7 (creator campaign browse/apply, Creator Week 2 sprint — TASK_INBOX.md P0 #7). Creator
 * identity always comes from the JWT-backed {@link AuthPrincipal} via
 * {@code CreatorContextService.requireCreatorProfile} inside {@link CreatorCampaignService} —
 * there is no creator-id path param on any of these routes to mistakenly trust.
 */
@RestController
@RequestMapping("/creator/campaigns")
public class CreatorCampaignController {

    private final CreatorCampaignService creatorCampaignService;

    public CreatorCampaignController(CreatorCampaignService creatorCampaignService) {
        this.creatorCampaignService = creatorCampaignService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<CreatorCampaignListItem>>> browse(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(required = false) String niche,
            @RequestParam(required = false) BigDecimal budgetMin,
            @RequestParam(required = false) BigDecimal budgetMax,
            @RequestParam(required = false) String platform,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int limit) {
        var result =
                creatorCampaignService.browse(principal, niche, budgetMin, budgetMax, platform, page, limit);
        return ResponseEntity.ok(ApiResponse.ok(result.items(), result.meta()));
    }

    @GetMapping("/{campaignId}")
    public ResponseEntity<ApiResponse<CreatorCampaignDetailResponse>> get(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable String campaignId) {
        return ResponseEntity.ok(ApiResponse.ok(creatorCampaignService.getDetail(principal, campaignId)));
    }

    @PostMapping("/{campaignId}/apply")
    public ResponseEntity<ApiResponse<ApplyResponse>> apply(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String campaignId,
            @Valid @RequestBody(required = false) ApplyRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(creatorCampaignService.apply(principal, campaignId, body)));
    }
}
