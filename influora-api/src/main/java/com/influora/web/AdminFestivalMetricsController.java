package com.influora.web;

import com.influora.security.AuthPrincipal;
import com.influora.service.admin.AdminFestivalMetricsService;
import com.influora.web.dto.admin.AdminFestivalMetricsDtos.CouponCopyMetricsResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Festival Box demand-signal metrics for the admin console (T-FESTIVALBOX-0905 phase 6). Mounted
 * at {@code /admin/festival-metrics} (full path {@code /api/v1/admin/festival-metrics/...}) — a
 * sibling of, not folded into, {@code AdminFestivalEnquiryController}: that controller owns the
 * enquiry pipeline resource, this one owns coupon-copy demand-signal reads over a different table.
 *
 * <p>Returns raw DTOs (unwrapped, no {@code ApiResponse} envelope), matching every other {@code
 * Admin*Controller}.
 *
 * <p>The role gate (SUPER_ADMIN + ADMIN, MFA satisfied) lives in {@link
 * AdminFestivalMetricsService}, not here — this codebase uses no {@code @PreAuthorize}.
 */
@RestController
@RequestMapping("/admin/festival-metrics")
public class AdminFestivalMetricsController {

    private final AdminFestivalMetricsService adminFestivalMetricsService;

    public AdminFestivalMetricsController(AdminFestivalMetricsService adminFestivalMetricsService) {
        this.adminFestivalMetricsService = adminFestivalMetricsService;
    }

    /**
     * GET /admin/festival-metrics/copies?edition= — per-sponsor totals + the daily series for one
     * edition's coupon-copy counts. This is an INTENT signal, never a sale — never merge its output
     * with redemption/sales data on the way to the client.
     */
    @GetMapping("/copies")
    public CouponCopyMetricsResponse copies(
            @AuthenticationPrincipal AuthPrincipal principal, @RequestParam String edition) {
        return adminFestivalMetricsService.getCopyMetrics(principal, edition);
    }
}
