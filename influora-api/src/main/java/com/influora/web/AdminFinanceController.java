package com.influora.web;

import com.influora.security.AuthPrincipal;
import com.influora.service.admin.AdminFinanceService;
import com.influora.web.dto.admin.AdminFinanceDtos.EscrowSummaryDto;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin Finance/Escrow console (Phase 2 per the CEO directive). Mounted at {@code /admin/finance}
 * (full path {@code /api/v1/admin/finance/...} given {@code server.servlet.context-path=/api/v1};
 * {@code src/admin/services/api-contracts.ts} calls these as {@code /finance/...} relative to its
 * {@code API_BASE = '/api/v1/admin'}). Shares the {@code /admin/finance} namespace with
 * {@code PlatformFeeAdminController} ({@code /admin/finance/fee-config}) — distinct subpaths, no
 * collision.
 *
 * <p>Role-gating and the read-only scope live in {@link AdminFinanceService}. Returns raw DTOs
 * (no {@link com.influora.common.ApiResponse} envelope) — same deliberate deviation as every other
 * {@code Admin*Controller}.
 */
@RestController
@RequestMapping("/admin/finance")
public class AdminFinanceController {

    private final AdminFinanceService adminFinanceService;

    public AdminFinanceController(AdminFinanceService adminFinanceService) {
        this.adminFinanceService = adminFinanceService;
    }

    /**
     * {@code GET /admin/finance/escrow} → {@code EscrowSummary} ({@code admin.types.ts}). Backs
     * {@code financeApi.getEscrowSummary()}. SUPER_ADMIN + ADMIN, MFA-gated (enforced in the
     * service).
     */
    @GetMapping("/escrow")
    public EscrowSummaryDto getEscrowSummary(@AuthenticationPrincipal AuthPrincipal principal) {
        return adminFinanceService.getEscrowSummary(principal);
    }
}
