package com.influora.web;

import com.influora.security.AuthPrincipal;
import com.influora.service.admin.AdminDashboardService;
import com.influora.service.admin.AdminRevenueService;
import com.influora.web.dto.admin.AdminDashboardDtos.CeoPulseDataDto;
import com.influora.web.dto.admin.AdminDashboardDtos.OperationsSummaryDto;
import com.influora.web.dto.admin.AdminRevenueDtos.RevenueSnapshotDto;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * CEO Pulse / dashboard stats API (src/admin/TASK_ASSIGNMENTS.md P0: "Dashboard stats API" —
 * Vikram). Mounted at {@code /admin/dashboard} (see {@code AdminAuthController} class javadoc for
 * the {@code /api/v1} vs. {@code api-contracts.ts}'s {@code /api/admin} base-path mismatch note —
 * same caveat applies here).
 *
 * <p>Returns raw DTOs (unwrapped, no {@link com.influora.common.ApiResponse} envelope) for the
 * same reason as {@code AdminAuthController} — matching {@code dashboardApi}'s
 * {@code apiRequest()} client contract in api-contracts.ts.
 *
 * <p>{@code getPulse}/{@code getOperationsSummary}/{@code getFinancialSummary} are implemented.
 * {@code getFinancialSummary} delegates to {@link AdminRevenueService} — every figure is a live
 * GMV/platform-fee aggregate (no fabricated data). {@code getMarketingSummary} is still NOT built:
 * it bundles acquisition/growth metrics that have no backing data (no ad-spend, no signup-source
 * attribution, no {@code Referral} table) — the SPA should not call that one yet.
 */
@RestController
@RequestMapping("/admin/dashboard")
public class AdminDashboardController {

    private final AdminDashboardService adminDashboardService;
    private final AdminRevenueService adminRevenueService;

    public AdminDashboardController(
            AdminDashboardService adminDashboardService, AdminRevenueService adminRevenueService) {
        this.adminDashboardService = adminDashboardService;
        this.adminRevenueService = adminRevenueService;
    }

    @GetMapping("/pulse")
    public CeoPulseDataDto pulse(@AuthenticationPrincipal AuthPrincipal principal) {
        return adminDashboardService.pulse(principal);
    }

    @GetMapping("/operations")
    public OperationsSummaryDto operations(@AuthenticationPrincipal AuthPrincipal principal) {
        return adminDashboardService.operations(principal);
    }

    /**
     * Financial summary — the last 12 buckets of revenue at the requested {@code period} granularity
     * ({@code day}/{@code week}/{@code month}/{@code quarter}). Backs {@code
     * dashboardApi.getFinancialSummary(period)}; returns a raw {@code RevenueSnapshot[]}. Live
     * GMV/platform-fee aggregates; derivation + role gate live in {@link
     * AdminRevenueService#getFinancialSummary}.
     */
    @GetMapping("/financial")
    public List<RevenueSnapshotDto> financial(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(defaultValue = "month") String period) {
        return adminRevenueService.getFinancialSummary(principal, period);
    }
}
