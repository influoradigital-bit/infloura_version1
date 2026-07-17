package com.influora.service.admin;

import com.influora.domain.entity.SupportTicket;
import com.influora.domain.enums.AdminRole;
import com.influora.domain.enums.CampaignStatus;
import com.influora.domain.enums.ContentFlagStatus;
import com.influora.domain.enums.CreatorApplicationStatus;
import com.influora.domain.enums.TicketStatus;
import com.influora.domain.enums.VerificationStatus;
import com.influora.domain.enums.WorkspaceType;
import com.influora.repository.CampaignRepository;
import com.influora.repository.ContentFlagRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.EscrowHoldRepository;
import com.influora.repository.SupportTicketRepository;
import com.influora.repository.UserRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.security.AuthPrincipal;
import com.influora.web.dto.admin.AdminDashboardDtos.CeoPulseDataDto;
import com.influora.web.dto.admin.AdminDashboardDtos.OperationsSummaryDto;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CEO Pulse / dashboard stats service (src/admin/TASK_ASSIGNMENTS.md P0: "Dashboard stats API").
 *
 * <p><b>What's real vs. placeholder, and why (do not treat the zeros as bugs):</b> the schema
 * (V2/V4/V9/V34) has no platform-fee ledger, no historical KPI snapshot table, and no
 * session/event-tracking table. So:
 * <ul>
 *   <li><b>Real, computed from live rows:</b> {@code activeCampaigns} (CampaignRepository),
 *       {@code escrowFloat} (EscrowHoldRepository, FUNDED sum), {@code gmv} (interim proxy — see
 *       {@code EscrowHoldRepository.sumAmountByStatusIn} javadoc), {@code supportQueueDepth}
 *       (SupportTicketRepository), {@code mauBrands}/{@code mauCreators} (login-recency proxy —
 *       see {@code UserRepository.countByUserTypeAndLastLoginAtAfter} javadoc), and one real
 *       {@code redFlags} entry ({@code SUPPORT_AGING}, from tickets open &gt;48h).
 *   <li><b>Honest zero + TODO, not fabricated:</b> {@code revenue} (no platform-fee-vs-GMV split
 *       exists anywhere in the schema — needs Rohan's formula per TASK_ASSIGNMENTS.md "TDS report
 *       requirements: Spec for Vikram"), and all four *Change WoW percentages (no snapshot
 *       mechanism to diff against — needs either a scheduled snapshot job or a dedicated
 *       kpi_snapshots table, neither of which exists).
 *   <li><b>Not implemented this cycle:</b> {@code ESCROW_LOW}/{@code SLA_BREACH}/
 *       {@code PAYOUT_DELAY}/{@code REVIEW_BACKLOG} red flags (need business thresholds owned by
 *       Rohan/product, not just a query), and the {@code getFinancialSummary}/
 *       {@code getMarketingSummary} contract methods — TASK_ASSIGNMENTS.md itself lists "Finance
 *       dashboards" under "Blocked Until Phase 1", so no controller endpoint exists for them yet.
 * </ul>
 */
@Service
public class AdminDashboardService {

    /** M-28 — window for {@code avgReviewTime}: resolved-ticket duration averaged over the last 30 days. */
    private static final long REVIEW_TIME_WINDOW_DAYS = 30;

    private final AdminContextService adminContext;
    private final AdminDashboardStatsCache statsCache;
    private final CampaignRepository campaignRepository;
    private final SupportTicketRepository supportTicketRepository;
    private final WorkspaceRepository workspaceRepository;
    private final CreatorProfileRepository creatorProfileRepository;
    private final ContentFlagRepository contentFlagRepository;

    public AdminDashboardService(
            AdminContextService adminContext,
            AdminDashboardStatsCache statsCache,
            CampaignRepository campaignRepository,
            SupportTicketRepository supportTicketRepository,
            WorkspaceRepository workspaceRepository,
            CreatorProfileRepository creatorProfileRepository,
            ContentFlagRepository contentFlagRepository) {
        this.adminContext = adminContext;
        this.statsCache = statsCache;
        this.campaignRepository = campaignRepository;
        this.supportTicketRepository = supportTicketRepository;
        this.workspaceRepository = workspaceRepository;
        this.creatorProfileRepository = creatorProfileRepository;
        this.contentFlagRepository = contentFlagRepository;
    }

    @Transactional(readOnly = true)
    public CeoPulseDataDto pulse(AuthPrincipal principal) {
        // Dashboard reads are open to all three admin tiers (Kabir's suggested matrix,
        // wiki/admin-progress/SECURITY-NOTES.md P0 item 1: SUPPORT = read access at minimum) --
        // MFA is still required for SUPER_ADMIN/ADMIN via requireRoleWithMfaSatisfied, but the
        // role check itself is an explicit allow-list now, not just "any admin." This check runs
        // UNCACHED on every call, cache hit or miss -- see AdminDashboardStatsCache's class javadoc
        // for why the actual query work (and only that) is what's behind the P1 Redis cache.
        adminContext.requireRoleWithMfaSatisfied(
                principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN, AdminRole.SUPPORT);

        return statsCache.pulseStats();
    }

    @Transactional(readOnly = true)
    public OperationsSummaryDto operations(AuthPrincipal principal) {
        // Same allow-list as pulse() above -- see comment there.
        adminContext.requireRoleWithMfaSatisfied(
                principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN, AdminRole.SUPPORT);

        long activeCampaigns = campaignRepository.countByStatus(CampaignStatus.ACTIVE);
        long supportQueueDepth =
                supportTicketRepository.countByStatusIn(
                        List.of(TicketStatus.OPEN, TicketStatus.IN_PROGRESS, TicketStatus.WAITING_USER));

        return new OperationsSummaryDto(
                activeCampaigns,
                // campaignsAtRisk -- still an honest 0 + TODO, not fabricated (see class javadoc):
                // "at risk" needs an SLA/timeline definition owned by Rohan/product, not just a
                // query this service can invent on its own.
                0,
                // M-28 fix: reviewBacklog was a hardcoded 0 -- real count across the same three
                // pending-approval sources ApprovalWorkflowService's queue surfaces (BRAND_KYC +
                // CREATOR_APPLICATION + CONTENT_MODERATION), via a true COUNT rather than that
                // service's own UI-bounded 50-row-per-type page.
                reviewBacklog(),
                supportQueueDepth,
                // M-28 fix: avgReviewTime was a hardcoded 0.0 -- real mean resolution duration
                // (hours) over tickets resolved in the last REVIEW_TIME_WINDOW_DAYS days.
                avgReviewTimeHours());
    }

    private long reviewBacklog() {
        long pendingKyc =
                workspaceRepository.countByTypeAndVerificationStatus(
                        WorkspaceType.BRAND, VerificationStatus.PENDING);
        long pendingApplications =
                creatorProfileRepository.countByApplicationStatus(CreatorApplicationStatus.PENDING);
        long pendingContentFlags = contentFlagRepository.countByStatus(ContentFlagStatus.PENDING);
        return pendingKyc + pendingApplications + pendingContentFlags;
    }

    private double avgReviewTimeHours() {
        Instant windowStart = Instant.now().minus(REVIEW_TIME_WINDOW_DAYS, ChronoUnit.DAYS);
        List<SupportTicket> resolvedInWindow =
                supportTicketRepository.findByResolvedAtIsNotNullAndResolvedAtAfter(windowStart);
        if (resolvedInWindow.isEmpty()) {
            return 0.0;
        }
        long totalHours =
                resolvedInWindow.stream()
                        .mapToLong(t -> ChronoUnit.HOURS.between(t.getCreatedAt(), t.getResolvedAt()))
                        .sum();
        return totalHours / (double) resolvedInWindow.size();
    }
}
