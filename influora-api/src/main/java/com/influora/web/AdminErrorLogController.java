package com.influora.web;

import com.influora.security.AuthPrincipal;
import com.influora.service.admin.AdminErrorLogService;
import com.influora.web.dto.admin.AdminErrorLogDtos.ErrorLogEntryDto;
import com.influora.web.dto.admin.AdminErrorLogDtos.ErrorStatsDto;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Error-log console API. {@code errorApi} (src/admin/services/api-contracts.ts, lines 654-671)
 * calls {@code GET /errors/recent}, {@code GET /errors/{id}}, {@code POST /errors/{id}/resolve} and
 * {@code GET /errors/stats} against {@code API_BASE = '/api/v1/admin'} — i.e. mounted here at
 * {@code /admin/errors}.
 *
 * <p>{@code /recent} and {@code /stats} are literal path segments; Spring matches them ahead of the
 * {@code /{id}} variable, so there is no routing collision. Raw DTOs (no {@link
 * com.influora.common.ApiResponse} envelope), same convention as every other {@code
 * Admin*Controller}. RBAC (SUPER_ADMIN + MFA) is enforced in {@link AdminErrorLogService}.
 */
@RestController
@RequestMapping("/admin/errors")
public class AdminErrorLogController {

    private final AdminErrorLogService adminErrorLogService;

    public AdminErrorLogController(AdminErrorLogService adminErrorLogService) {
        this.adminErrorLogService = adminErrorLogService;
    }

    @GetMapping("/recent")
    public List<ErrorLogEntryDto> recent(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        return adminErrorLogService.getRecent(principal, limit);
    }

    @GetMapping("/stats")
    public ErrorStatsDto stats(@AuthenticationPrincipal AuthPrincipal principal) {
        return adminErrorLogService.getStats(principal);
    }

    @GetMapping("/{id}")
    public ErrorLogEntryDto getById(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable("id") String id) {
        return adminErrorLogService.getById(principal, id);
    }

    @PostMapping("/{id}/resolve")
    public ErrorLogEntryDto resolve(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable("id") String id) {
        return adminErrorLogService.resolve(principal, id);
    }
}
