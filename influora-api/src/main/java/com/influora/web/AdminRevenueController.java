package com.influora.web;

import com.influora.security.AuthPrincipal;
import com.influora.service.admin.AdminRevenueService;
import com.influora.web.dto.admin.AdminRevenueDtos.RevenueSnapshotDto;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin revenue report, mounted at {@code /admin/finance} (full path {@code
 * /api/v1/admin/finance/...}). A separate controller in the same namespace as {@code
 * AdminFinanceController} / {@code PlatformFeeAdminController} — distinct subpath ({@code /revenue}),
 * no route collision (same pattern those two already use). Returns raw DTOs (no {@code ApiResponse}
 * envelope), matching {@code financeApi}'s {@code apiRequest()} client contract.
 */
@RestController
@RequestMapping("/admin/finance")
public class AdminRevenueController {

    private final AdminRevenueService adminRevenueService;

    public AdminRevenueController(AdminRevenueService adminRevenueService) {
        this.adminRevenueService = adminRevenueService;
    }

    /**
     * {@code GET /admin/finance/revenue?startDate&endDate&groupBy} → {@code RevenueSnapshot[]}. Backs
     * {@code financeApi.getRevenue()}. GMV + platform fees are live aggregates; {@code setupFees} is a
     * structural zero (no setup-fee product). Derivation + role gate live in {@link
     * AdminRevenueService#getRevenue}.
     */
    @GetMapping("/revenue")
    public List<RevenueSnapshotDto> getRevenue(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam String startDate,
            @RequestParam String endDate,
            @RequestParam(defaultValue = "day") String groupBy) {
        return adminRevenueService.getRevenue(principal, startDate, endDate, groupBy);
    }
}
