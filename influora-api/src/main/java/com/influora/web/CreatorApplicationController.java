package com.influora.web;

import com.influora.common.ApiResponse;
import com.influora.security.AuthPrincipal;
import com.influora.service.CreatorApplicationService;
import com.influora.web.dto.creatorcampaign.CreatorApplicationDtos.CreatorApplicationListItem;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * "My Applications" page (my-applications-plan-2026-07-24.md). Creator identity always comes from
 * the JWT-backed {@link AuthPrincipal} via {@code CreatorContextService.requireCreatorProfile}
 * inside {@link CreatorApplicationService} — there is no creator-id path/query param here to
 * mistakenly trust (Kabir R1 IDOR gate).
 */
@RestController
@RequestMapping("/creator/applications")
public class CreatorApplicationController {

    private final CreatorApplicationService creatorApplicationService;

    public CreatorApplicationController(CreatorApplicationService creatorApplicationService) {
        this.creatorApplicationService = creatorApplicationService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<CreatorApplicationListItem>>> list(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int limit) {
        var result = creatorApplicationService.list(principal, status, page, limit);
        return ResponseEntity.ok(ApiResponse.ok(result.items(), result.meta()));
    }
}
