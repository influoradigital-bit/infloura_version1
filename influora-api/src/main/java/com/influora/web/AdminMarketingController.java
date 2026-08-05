package com.influora.web;

import com.influora.security.AuthPrincipal;
import com.influora.service.admin.AdminMarketingService;
import com.influora.web.dto.admin.AdminMarketingDtos.GrowthMetricsDto;
import com.influora.web.dto.admin.AdminMarketingDtos.PlatformReputationScoreDto;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Marketing/analytics API for the admin panel. Mounted at {@code /admin/marketing} (full path
 * {@code /api/v1/admin/marketing/...} given {@code server.servlet.context-path=/api/v1} — same
 * convention as every other {@code Admin*Controller}). Returns raw DTOs (unwrapped, no {@link
 * com.influora.common.ApiResponse} envelope), matching {@code marketingApi}'s {@code apiRequest()}
 * client contract in {@code src/admin/services/api-contracts.ts}.
 *
 * <p><b>Reputation and growth are implemented.</b> {@code marketingApi.getAcquisition}/{@code
 * getReferrals} have no backing data (no ad-spend, no signup-source attribution, no {@code
 * Referral} table) and are deliberately not served rather than fabricated — see {@link
 * com.influora.web.dto.admin.AdminMarketingDtos} class javadoc. Growth itself ships a real-data
 * subset — see {@link com.influora.web.dto.admin.AdminMarketingDtos.GrowthMetricsDto}.
 */
@RestController
@RequestMapping("/admin/marketing")
public class AdminMarketingController {

    private final AdminMarketingService adminMarketingService;

    public AdminMarketingController(AdminMarketingService adminMarketingService) {
        this.adminMarketingService = adminMarketingService;
    }

    /**
     * Platform reputation snapshot (review-quality averages + dispute resolution speed). Backs
     * {@code marketingApi.getReputation()}. Every field is a live aggregate over real {@code
     * Review}/{@code Dispute} rows. Role gate (SUPER_ADMIN + ADMIN, MFA) lives in the service.
     */
    @GetMapping("/reputation")
    public PlatformReputationScoreDto getReputation(@AuthenticationPrincipal AuthPrincipal principal) {
        return adminMarketingService.getReputation(principal);
    }

    /**
     * Growth funnel snapshot (signups, brand first/repeat-campaign funnel, creator-approval and
     * brand-activation conversion rates). Backs {@code marketingApi.getGrowth()}. A real-data subset
     * of the FE's {@code GrowthMetrics} shape — see {@link
     * com.influora.web.dto.admin.AdminMarketingDtos.GrowthMetricsDto} for exactly what's served and
     * what's omitted for lack of backing data. Role gate (SUPER_ADMIN + ADMIN, MFA) lives in the
     * service.
     */
    @GetMapping("/growth")
    public GrowthMetricsDto getGrowth(@AuthenticationPrincipal AuthPrincipal principal) {
        return adminMarketingService.getGrowth(principal);
    }
}
