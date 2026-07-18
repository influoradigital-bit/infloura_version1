package com.influora.web;

import com.influora.security.AuthPrincipal;
import com.influora.service.admin.AdminSupportService;
import com.influora.web.dto.admin.AdminSupportDtos.SupportStatsDto;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Support stats API. {@code supportApi.getStats()} (src/admin/services/api-contracts.ts, lines
 * 526-533) calls {@code GET /support/stats} against {@code API_BASE = '/api/v1/admin'}, i.e. the
 * full path must resolve to {@code /admin/support/stats} exactly. {@code AdminSupportController}
 * is mounted at {@code /admin/support/tickets}, so a {@code @GetMapping("/stats")} added there
 * would resolve to {@code /admin/support/tickets/stats} instead — Spring concatenates
 * method-level mappings onto the class-level {@code @RequestMapping}, there is no way to "escape"
 * it from a method annotation. This sibling controller is mounted one level up ({@code
 * /admin/support}) so the single {@code /stats} route lands exactly where the frontend calls it,
 * without touching {@code AdminSupportController}'s existing {@code /tickets/**} routes.
 *
 * <p>Returns a raw {@code SupportStatsDto} (unwrapped, no {@link com.influora.common.ApiResponse}
 * envelope) — same convention as every other {@code Admin*Controller}.
 */
@RestController
@RequestMapping("/admin/support")
public class AdminSupportStatsController {

    private final AdminSupportService adminSupportService;

    public AdminSupportStatsController(AdminSupportService adminSupportService) {
        this.adminSupportService = adminSupportService;
    }

    @GetMapping("/stats")
    public SupportStatsDto getStats(@AuthenticationPrincipal AuthPrincipal principal) {
        return adminSupportService.getStats(principal);
    }
}
