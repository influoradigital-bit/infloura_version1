package com.influora.web;

import com.influora.security.AuthPrincipal;
import com.influora.service.admin.AdminCampaignService;
import com.influora.web.dto.admin.AdminCampaignDtos.CampaignSummaryDto;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Campaign-monitoring API (src/admin/TASK_ASSIGNMENTS.md P2 → wiki/reports/2026-07-12/tasks/P2-7 —
 * Vikram, this cycle). Mounted at {@code /admin/campaigns} (full path
 * {@code /api/v1/admin/campaigns/...} given {@code server.servlet.context-path=/api/v1} — same
 * convention/caveat as every other {@code Admin*Controller}, see their class javadocs for the
 * {@code /api/admin} vs. {@code /api/v1/admin} path note).
 *
 * <p>Returns raw DTOs (unwrapped, no {@link com.influora.common.ApiResponse} envelope) — same
 * deliberate deviation as {@code AdminAuthController}/{@code AdminDashboardController}/{@code
 * AdminBrandController}, to match {@code campaignApi}'s {@code apiRequest()} client contract in
 * {@code src/admin/services/api-contracts.ts} (line 60's {@code apiRequest} treats the entire JSON
 * response body as the payload).
 *
 * <p>Swap-in target for {@code useCampaignList.ts}'s line 6 TODO ("backend campaign-monitoring
 * endpoint... is not built yet") — that hook is currently mocked with a fixture shaped exactly like
 * {@link CampaignSummaryDto} (see {@code getMockCampaignList()} lines 27-148), so once Ananya
 * wires the real {@code campaignApi.list()} call to this endpoint, the frontend state-shape
 * remains unchanged and the table Just Works™.
 *
 * <p>Only the {@code GET /admin/campaigns} list endpoint is implemented this cycle (P2-7
 * acceptance: "real endpoint returns campaign data"). No pagination/filters/sort params yet (the
 * FE sorts/filters client-side today per {@code useCampaignList.ts} lines 208-243) — follow-up
 * cycles can add server-side search/pagination once the admin-panel campaign-search UX is ratified
 * and spec'd.
 */
@RestController
@RequestMapping("/admin/campaigns")
public class AdminCampaignController {

    private final AdminCampaignService adminCampaignService;

    public AdminCampaignController(AdminCampaignService adminCampaignService) {
        this.adminCampaignService = adminCampaignService;
    }

    /**
     * Returns all campaigns (admin-panel-wide view, every workspace). Role-guarded:
     * {@code SUPPORT}/{@code ADMIN}/{@code SUPER_ADMIN}, MFA satisfied for ADMIN+.
     *
     * <p>P2-7 MVP: no filters/sort/pagination params — returns everything. Future cycles can
     * accept {@code CampaignFilters}/{@code Pageable} params once the admin-panel campaign-search
     * UX is fully spec'd.
     */
    @GetMapping
    public List<CampaignSummaryDto> list(@AuthenticationPrincipal AuthPrincipal principal) {
        return adminCampaignService.list(principal);
    }
}
