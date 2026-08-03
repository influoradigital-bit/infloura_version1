package com.influora.service.admin;

import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.Dispute;
import com.influora.domain.entity.EscrowHold;
import com.influora.domain.enums.AdminRole;
import com.influora.domain.enums.EscrowStatus;
import com.influora.repository.CampaignRepository;
import com.influora.repository.DisputeRepository;
import com.influora.repository.EscrowHoldRepository;
import com.influora.security.AuthPrincipal;
import com.influora.web.dto.admin.AdminFinanceDtos.EscrowSummaryDto;
import com.influora.web.dto.admin.AdminFinanceDtos.FlaggedEscrowDto;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
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
    private static final String FROZEN_NO_DISPUTE_REASON = "Frozen — no linked dispute";
    private static final String UNKNOWN_CAMPAIGN = "(unknown campaign)";

    private final AdminContextService adminContext;
    private final EscrowHoldRepository escrowHoldRepository;
    private final CampaignRepository campaignRepository;
    private final DisputeRepository disputeRepository;

    public AdminFinanceService(
            AdminContextService adminContext,
            EscrowHoldRepository escrowHoldRepository,
            CampaignRepository campaignRepository,
            DisputeRepository disputeRepository) {
        this.adminContext = adminContext;
        this.escrowHoldRepository = escrowHoldRepository;
        this.campaignRepository = campaignRepository;
        this.disputeRepository = disputeRepository;
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

    /**
     * {@code GET /admin/escrow/flagged} — every {@code FROZEN} escrow hold with its campaign name and
     * flag reason attached (see {@link FlaggedEscrowDto} for per-field derivation and the declared
     * fallbacks). Read-only; moves no money. Campaign titles and dispute reasons are BATCH-loaded
     * (at most two extra queries total, never one-per-row).
     */
    @Transactional(readOnly = true)
    public List<FlaggedEscrowDto> getFlaggedEscrows(AuthPrincipal principal) {
        adminContext.requireRoleWithMfaSatisfied(principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN);

        List<EscrowHold> frozen =
                escrowHoldRepository.findByStatusOrderByCreatedAtDesc(EscrowStatus.FROZEN);
        if (frozen.isEmpty()) {
            return List.of();
        }

        Map<String, String> titleByCampaignId =
                campaignRepository
                        .findAllById(
                                frozen.stream()
                                        .map(EscrowHold::getCampaignId)
                                        .filter(Objects::nonNull)
                                        .distinct()
                                        .toList())
                        .stream()
                        .collect(Collectors.toMap(Campaign::getId, Campaign::getTitle));

        // Most-recent dispute reason per collaboration — a hold is FROZEN precisely because a dispute
        // was opened on its collaboration (EscrowService.freezeUnreleasedForDispute).
        Map<String, String> reasonByCollaborationId =
                disputeRepository
                        .findByCollaborationIdIn(
                                frozen.stream()
                                        .map(EscrowHold::getCollaborationId)
                                        .filter(Objects::nonNull)
                                        .distinct()
                                        .toList())
                        .stream()
                        .sorted(Comparator.comparing(Dispute::getCreatedAt).reversed())
                        .collect(
                                Collectors.toMap(
                                        Dispute::getCollaborationId,
                                        Dispute::getReason,
                                        (mostRecent, older) -> mostRecent));

        return frozen.stream()
                .map(
                        hold ->
                                new FlaggedEscrowDto(
                                        hold.getId(),
                                        hold.getCampaignId(),
                                        hold.getCampaignId() == null
                                                ? null
                                                : titleByCampaignId.getOrDefault(
                                                        hold.getCampaignId(), UNKNOWN_CAMPAIGN),
                                        hold.getAmount() == null ? 0.0 : hold.getAmount().doubleValue(),
                                        hold.getCollaborationId() == null
                                                ? FROZEN_NO_DISPUTE_REASON
                                                : reasonByCollaborationId.getOrDefault(
                                                        hold.getCollaborationId(), FROZEN_NO_DISPUTE_REASON),
                                        hold.getCreatedAt() == null
                                                ? null
                                                : hold.getCreatedAt().toString()))
                .toList();
    }
}
