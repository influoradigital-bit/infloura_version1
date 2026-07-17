package com.influora.web;

import com.influora.security.AuthPrincipal;
import com.influora.service.admin.AdminDashboardService;
import com.influora.web.dto.admin.AdminDashboardDtos.CeoPulseDataDto;
import com.influora.web.dto.admin.AdminDashboardDtos.OperationsSummaryDto;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
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
 * <p>Only {@code getPulse}/{@code getOperationsSummary} are implemented this cycle.
 * {@code getFinancialSummary} and {@code getMarketingSummary} (also declared in
 * {@code dashboardApi}) are NOT — TASK_ASSIGNMENTS.md's own Phase 1 section lists finance
 * dashboards under "Blocked Until Phase 1", and the marketing summary depends on Rohan/Tejas's
 * still-pending acquisition/growth formulas. No stub route exists for either; the SPA should not
 * call them yet.
 */
@RestController
@RequestMapping("/admin/dashboard")
public class AdminDashboardController {

    private final AdminDashboardService adminDashboardService;

    public AdminDashboardController(AdminDashboardService adminDashboardService) {
        this.adminDashboardService = adminDashboardService;
    }

    @GetMapping("/pulse")
    public CeoPulseDataDto pulse(@AuthenticationPrincipal AuthPrincipal principal) {
        return adminDashboardService.pulse(principal);
    }

    @GetMapping("/operations")
    public OperationsSummaryDto operations(@AuthenticationPrincipal AuthPrincipal principal) {
        return adminDashboardService.operations(principal);
    }
}
