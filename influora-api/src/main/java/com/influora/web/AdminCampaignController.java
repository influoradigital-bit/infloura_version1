package com.influora.web;

import com.influora.security.AuthPrincipal;
import com.influora.service.admin.AdminCampaignService;
import com.influora.service.admin.AdminCampaignService.PagedCampaignSummaries;
import com.influora.web.dto.admin.AdminCampaignDtos.CampaignDetailDto;
import com.influora.web.dto.admin.AdminCampaignDtos.CampaignSummaryDto;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Campaign-monitoring API (src/admin/TASK_ASSIGNMENTS.md P2 → wiki/reports/2026-07-12/tasks/P2-7 —
 * Vikram, this cycle). Mounted at {@code /admin/campaigns} (full path
 * {@code /api/v1/admin/campaigns/...} given {@code server.servlet.context-path=/api/v1} — same
 * convention/caveat as every other {@code Admin*Controller}, see their class javadocs for the
 * {@code /api/admin} vs. {@code /api/v1/admin} path note).
 *
 * <p>Returns a raw {@code CampaignSummaryDto[]} (unwrapped, no {@link com.influora.common.ApiResponse}
 * envelope) — same deliberate deviation as {@code AdminAuthController}/{@code
 * AdminDashboardController}/{@code AdminBrandController}, to match {@code campaignApi}'s {@code
 * apiRequest()} client contract in {@code src/admin/services/api-contracts.ts} (line 60's {@code
 * apiRequest} treats the entire JSON response body as the payload).
 *
 * <p><b>M-20 (this pass):</b> {@code page}/{@code pageSize} are now real, bounded query params
 * (previously the service silently capped at a fixed 200-row page with no way to move past it).
 * The response body stays a plain array — changing it to a {@code {items, meta}} envelope would be
 * a breaking contract change for {@code useCampaignList.ts}, which reads {@code campaignApi.list()}
 * as {@code CampaignSummary[]} — so the true total row count is instead carried on an {@code
 * X-Total-Count} response header (a caller that ignores it gets the exact same array shape as
 * before; a caller that wants real pagination controls can read it).
 */
@RestController
@RequestMapping("/admin/campaigns")
public class AdminCampaignController {

    private final AdminCampaignService adminCampaignService;

    public AdminCampaignController(AdminCampaignService adminCampaignService) {
        this.adminCampaignService = adminCampaignService;
    }

    /**
     * Campaigns (admin-panel-wide view, every workspace), one bounded page at a time.
     * Role-guarded: {@code SUPPORT}/{@code ADMIN}/{@code SUPER_ADMIN}, MFA satisfied for ADMIN+.
     * {@code page} 1-based, defaults to 1; {@code pageSize} defaults to 50, capped at 200.
     */
    @GetMapping
    public ResponseEntity<List<CampaignSummaryDto>> list(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int pageSize) {
        PagedCampaignSummaries result = adminCampaignService.list(principal, page, pageSize);
        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(result.totalElements()))
                .header("X-Page", String.valueOf(result.page()))
                .header("X-Page-Size", String.valueOf(result.pageSize()))
                .body(result.items());
    }

    /**
     * Single-campaign detail, backing {@code campaignApi.getById} (api-contracts.ts:313-314).
     * Same role gate as {@link #list}. Returns a raw {@code CampaignDetailDto} (unwrapped), same
     * convention as {@link #list} and every other {@code Admin*Controller}.
     */
    @GetMapping("/{id}")
    public CampaignDetailDto getById(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable String id) {
        return adminCampaignService.getById(principal, id);
    }
}
