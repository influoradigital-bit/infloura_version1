package com.influora.service.admin;

import com.influora.domain.enums.AdminRole;
import com.influora.domain.enums.EscrowStatus;
import com.influora.repository.EscrowHoldRepository;
import com.influora.security.AuthPrincipal;
import com.influora.web.dto.admin.AdminFinanceDtos.EscrowSummaryDto;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Backs {@code AdminFinanceController} — the admin Finance/Escrow console (Phase 2 per the CEO
 * directive; see {@code PlatformFeeAdminController} class javadoc for why {@code financeApi} is
 * mounted under {@code /admin/finance/*}).
 *
 * <p><b>Role-gating:</b> {@code SUPER_ADMIN} + {@code ADMIN}, MFA-satisfied — financial visibility,
 * excluded from {@code SUPPORT}. Same manual per-call {@link AdminContextService} pattern as every
 * other {@code Admin*Service} (no {@code @PreAuthorize} anywhere in this codebase).
 *
 * <p>Read-only: this first endpoint only READS {@code escrow_holds}; it moves no money and takes no
 * lock. Escrow release/hold/refund actions are separate queued items and get their own confirm.
 */
@Service
public class AdminFinanceService {

    private static final double SECONDS_PER_HOUR = 3600.0;

    private final AdminContextService adminContext;
    private final EscrowHoldRepository escrowHoldRepository;

    public AdminFinanceService(
            AdminContextService adminContext, EscrowHoldRepository escrowHoldRepository) {
        this.adminContext = adminContext;
        this.escrowHoldRepository = escrowHoldRepository;
    }

    /**
     * {@code GET /admin/finance/escrow} — platform-wide escrow summary. Every figure is derived live
     * from {@code escrow_holds}; see {@link EscrowSummaryDto} for the exact per-field mapping and the
     * declared {@code pendingRelease} assumption.
     */
    @Transactional(readOnly = true)
    public EscrowSummaryDto getEscrowSummary(AuthPrincipal principal) {
        adminContext.requireRoleWithMfaSatisfied(principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN);

        BigDecimal totalLocked = escrowHoldRepository.sumAmountByStatusIn(List.of(EscrowStatus.FUNDED));
        long pendingRelease = escrowHoldRepository.countByStatus(EscrowStatus.FUNDED);
        long flaggedTransactions = escrowHoldRepository.countByStatus(EscrowStatus.FROZEN);
        Double avgReleaseSeconds = escrowHoldRepository.avgReleaseSeconds();

        return new EscrowSummaryDto(
                totalLocked == null ? 0.0 : totalLocked.doubleValue(),
                pendingRelease,
                flaggedTransactions,
                avgReleaseSeconds == null ? 0.0 : avgReleaseSeconds / SECONDS_PER_HOUR);
    }
}
