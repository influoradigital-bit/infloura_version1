package com.influora.web;

import com.influora.security.AuthPrincipal;
import com.influora.service.admin.AdminFinanceService;
import com.influora.web.dto.admin.AdminFinanceDtos.FlaggedEscrowDto;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin escrow console, mounted at {@code /admin/escrow} (full path {@code /api/v1/admin/escrow/...}
 * given {@code server.servlet.context-path=/api/v1}; {@code escrowApi} in
 * {@code src/admin/services/api-contracts.ts} calls it as {@code /escrow/...} relative to its
 * {@code API_BASE = '/api/v1/admin'}).
 *
 * <p><b>Scope note:</b> this controller intentionally exposes ONLY the read
 * ({@code GET /flagged}). The mutating {@code /{id}/hold|release|refund} routes {@code escrowApi}
 * also declares are deliberately NOT built here — direct escrow manipulation vs the audited
 * dispute-mediated model ({@code EscrowService.adminReleaseForDispute}, via
 * {@code AdminDisputeController}) is an open design decision (see
 * {@code .proof-os/tasks/admin-finance-queue/SCOPE.md}, Tier B). Role-gating and the read-only scope
 * live in {@link AdminFinanceService}. Returns raw DTOs, no {@code ApiResponse} envelope — same
 * deliberate deviation as every other {@code Admin*Controller}.
 */
@RestController
@RequestMapping("/admin/escrow")
public class AdminEscrowController {

    private final AdminFinanceService adminFinanceService;

    public AdminEscrowController(AdminFinanceService adminFinanceService) {
        this.adminFinanceService = adminFinanceService;
    }

    /**
     * {@code GET /admin/escrow/flagged} → array of flagged (FROZEN) holds. Backs
     * {@code escrowApi.getFlagged()}. SUPER_ADMIN + ADMIN, MFA-gated (enforced in the service).
     */
    @GetMapping("/flagged")
    public List<FlaggedEscrowDto> getFlagged(@AuthenticationPrincipal AuthPrincipal principal) {
        return adminFinanceService.getFlaggedEscrows(principal);
    }
}
