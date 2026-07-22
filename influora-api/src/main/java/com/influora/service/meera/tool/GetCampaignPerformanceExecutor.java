package com.influora.service.meera.tool;

import com.influora.common.ApiException;
import com.influora.domain.entity.AffiliateEarning;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.CreatorScore;
import com.influora.domain.entity.DeliverableMetric;
import com.influora.domain.entity.UtmCampaign;
import com.influora.domain.enums.CollaborationSource;
import com.influora.domain.enums.CollaborationStatus;
import com.influora.domain.enums.EscrowStatus;
import com.influora.repository.AffiliateEarningRepository;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.CreatorScoreRepository;
import com.influora.repository.DeliverableMetricRepository;
import com.influora.repository.EscrowHoldRepository;
import com.influora.repository.UtmCampaignRepository;
import com.influora.service.AuditLogService;
import com.influora.web.dto.meera.MeeraToolDtos.DeliverablePerformanceEntry;
import com.influora.web.dto.meera.MeeraToolDtos.GetCampaignPerformanceResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * R-tier executor for Phase 2 item 2.2 (Meera: Label-to-Moat build plan §2.2; DTO shape locked by
 * {@code wiki/build/phase2-priya-review.md} §2 Q2). Aggregates real, server-authoritative rows for
 * ONE campaign owned by the calling workspace — no money moves, no repository write beyond audit
 * logging.
 *
 * <p><b>SR-1 (never the {@code FUNDED_STATUSES} campaign-status proxy):</b> {@code spendInr} is
 * {@code SUM(EscrowHold.amount)} strictly {@code WHERE status = RELEASED}; {@code verifiedReach}
 * and every {@link DeliverablePerformanceEntry} are filtered to {@code
 * DeliverableMetric.SOURCE_PLATFORM_VERIFIED} rows only. No self-reported number ever reaches this
 * result (mirrors the outcome digest's {@code BrandContextAssembler#buildOutcomeEntry} discipline
 * exactly — see that class for the identical filtering rationale).
 *
 * <p><b>IDOR — the top risk (Kabir's mandatory Phase-2 gate):</b> the only repository call that can
 * resolve a {@link Campaign} here is {@link CampaignRepository#findByIdAndWorkspaceId}, which
 * returns empty for BOTH "no such campaign" and "campaign belongs to another workspace" — there is
 * no separate existence-check call that could leak a distinguishing 403/404 split. A cross-tenant
 * probe and a typo'd id produce byte-identical 404s (same shape {@code
 * ConfirmLaunchExecutor}/{@code CreateCampaignExecutor} already use for campaign-intent
 * resolution).
 *
 * <p><b>PII strip:</b> {@link DeliverablePerformanceEntry} carries only an opaque {@code
 * milestoneId} + numeric metrics — never a creator name, IG handle, caption, or any other
 * free-text field.
 */
@Service
public class GetCampaignPerformanceExecutor {

    /** ROI expressed as a ratio (1.4 == +40% return) — Priya's Q2 ruling, never a percentage string. */
    private static final int ROI_SCALE = 4;

    /** {@code CollaborationStatus} values that count as "the creator has not yet responded". */
    private static final Set<CollaborationStatus> PENDING_RESPONSE_STATUSES =
            Set.of(CollaborationStatus.INVITED, CollaborationStatus.CANCELLED);

    private final CampaignRepository campaignRepository;
    private final CollaborationRepository collaborationRepository;
    private final DeliverableMetricRepository deliverableMetricRepository;
    private final EscrowHoldRepository escrowHoldRepository;
    private final UtmCampaignRepository utmCampaignRepository;
    private final AffiliateEarningRepository affiliateEarningRepository;
    private final CreatorProfileRepository creatorProfileRepository;
    private final CreatorScoreRepository creatorScoreRepository;
    private final AuditLogService auditLogService;

    public GetCampaignPerformanceExecutor(
            CampaignRepository campaignRepository,
            CollaborationRepository collaborationRepository,
            DeliverableMetricRepository deliverableMetricRepository,
            EscrowHoldRepository escrowHoldRepository,
            UtmCampaignRepository utmCampaignRepository,
            AffiliateEarningRepository affiliateEarningRepository,
            CreatorProfileRepository creatorProfileRepository,
            CreatorScoreRepository creatorScoreRepository,
            AuditLogService auditLogService) {
        this.campaignRepository = campaignRepository;
        this.collaborationRepository = collaborationRepository;
        this.deliverableMetricRepository = deliverableMetricRepository;
        this.escrowHoldRepository = escrowHoldRepository;
        this.utmCampaignRepository = utmCampaignRepository;
        this.affiliateEarningRepository = affiliateEarningRepository;
        this.creatorProfileRepository = creatorProfileRepository;
        this.creatorScoreRepository = creatorScoreRepository;
        this.auditLogService = auditLogService;
    }

    @Transactional(readOnly = true)
    public GetCampaignPerformanceResult execute(String workspaceId, Map<String, Object> input) {
        String campaignId = stringArg(input, "campaign_id");
        if (campaignId == null || campaignId.isBlank()) {
            throw new ApiException(
                    "CAMPAIGN_ID_REQUIRED", "get_campaign_performance requires campaign_id", HttpStatus.BAD_REQUEST);
        }

        // IDOR fix (Kabir mandatory gate): resolve-scoped-by-workspace, ONE call, generic 404 on
        // miss OR cross-tenant id — never a separate existence check that could distinguish the
        // two cases via status code.
        Campaign campaign =
                campaignRepository
                        .findByIdAndWorkspaceId(campaignId, workspaceId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "CAMPAIGN_NOT_FOUND", "Campaign not found", HttpStatus.NOT_FOUND));

        List<Collaboration> collaborations = collaborationRepository.findByCampaignId(campaign.getId());
        List<String> collaborationIds = collaborations.stream().map(Collaboration::getId).toList();

        List<DeliverableMetric> verifiedMetrics =
                (collaborationIds.isEmpty()
                        ? List.<DeliverableMetric>of()
                        : deliverableMetricRepository.findByCollaborationIdIn(collaborationIds))
                        .stream()
                        .filter(m -> DeliverableMetric.SOURCE_PLATFORM_VERIFIED.equals(m.getSource()))
                        .toList();

        BigDecimal spendInr = escrowHoldRepository.sumAmountByCampaignIdAndStatus(campaign.getId(), EscrowStatus.RELEASED);
        if (spendInr == null) {
            spendInr = BigDecimal.ZERO;
        }

        List<UtmCampaign> utmRows = utmCampaignRepository.findByCampaignId(campaign.getId());
        BigDecimal attributedRevenueInr = sumRevenue(utmRows);

        List<AffiliateEarning> settledEarnings =
                affiliateEarningRepository.findByCampaignIdAndStatus(campaign.getId(), AffiliateEarning.Status.SETTLED);
        BigDecimal settledCommissionInr = sumCommission(settledEarnings);

        Long verifiedReach = verifiedMetrics.isEmpty() ? null : sumReach(verifiedMetrics);

        long creatorCount = collaborations.stream().map(Collaboration::getCreatorId).distinct().count();

        BigDecimal roi = computeRoi(spendInr, attributedRevenueInr);
        Double responseRate = computeResponseRate(collaborations);
        Double avgCreatorScore = computeAvgCreatorScore(collaborations);

        // PII strip (Kabir mandatory gate): opaque milestoneId + numeric metrics only — never a
        // creator name/IG handle/caption/any free-text field.
        List<DeliverablePerformanceEntry> deliverables =
                verifiedMetrics.stream()
                        .map(
                                m ->
                                        new DeliverablePerformanceEntry(
                                                m.getMilestoneId(), m.getReach(), m.getImpressions(), m.getEngagements()))
                        .toList();

        auditLogService.recordToolCall(
                workspaceId,
                "get_campaign_performance",
                "R",
                AuditLogService.OUTCOME_ALLOWED,
                null,
                null,
                null,
                Map.of("campaignId", campaign.getId()));

        return new GetCampaignPerformanceResult(
                campaign.getId(),
                creatorCount,
                spendInr,
                verifiedReach,
                attributedRevenueInr,
                settledCommissionInr,
                roi,
                responseRate,
                avgCreatorScore,
                // v1 always PLATFORM_VERIFIED — every field above is drawn exclusively from an
                // authoritative server record (see class javadoc); SELF_REPORTED is reserved for a
                // future fast-follow, never produced by this executor today.
                DeliverableMetric.SOURCE_PLATFORM_VERIFIED,
                deliverables);
    }

    /** {@code null} when spend is zero or there is no attributed revenue — never a divide-by-zero guess. */
    private static BigDecimal computeRoi(BigDecimal spendInr, BigDecimal attributedRevenueInr) {
        if (spendInr == null || spendInr.signum() == 0 || attributedRevenueInr == null) {
            return null;
        }
        return attributedRevenueInr.divide(spendInr, ROI_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * {@code accepted invites / total invites}, 0..1. "Invite" = a brand-initiated {@link
     * Collaboration} ({@link CollaborationSource#INVITATION}) for this campaign — creator-initiated
     * {@link CollaborationSource#APPLICATION} rows are not invites and are excluded from both the
     * numerator and denominator. "Accepted" = the creator responded and did not cancel (any status
     * beyond the initial {@code INVITED} pending state, excluding {@code CANCELLED}) — a
     * still-pending or declined invite does not count. {@code null} when there were no invites at
     * all (never a misleading 0%).
     */
    private static Double computeResponseRate(List<Collaboration> collaborations) {
        List<Collaboration> invites =
                collaborations.stream().filter(c -> c.getSource() == CollaborationSource.INVITATION).toList();
        if (invites.isEmpty()) {
            return null;
        }
        long accepted = invites.stream().filter(c -> !PENDING_RESPONSE_STATUSES.contains(c.getStatus())).count();
        return accepted / (double) invites.size();
    }

    /**
     * Average of each distinct creator's most recent {@code CreatorScore.qualityScore} (0..100) —
     * the same field {@code AdminCreatorService#latestQualityScore}/{@code CreatorScoresResponse}
     * treat as "the" overall creator score elsewhere in this codebase, so this reuses an existing
     * canonical number rather than inventing a new scoring formula. {@code null} when no campaign
     * creator has a computed score yet.
     */
    private Double computeAvgCreatorScore(List<Collaboration> collaborations) {
        Set<String> creatorUserIds = new LinkedHashSet<>();
        for (Collaboration c : collaborations) {
            if (c.getCreatorId() != null) {
                creatorUserIds.add(c.getCreatorId());
            }
        }
        if (creatorUserIds.isEmpty()) {
            return null;
        }
        BigDecimal total = BigDecimal.ZERO;
        int scored = 0;
        for (String creatorUserId : creatorUserIds) {
            CreatorProfile profile = creatorProfileRepository.findByUserId(creatorUserId).orElse(null);
            if (profile == null) {
                continue;
            }
            CreatorScore score =
                    creatorScoreRepository.findFirstByCreatorProfileIdOrderByTimeDesc(profile.getId()).orElse(null);
            if (score == null || score.getQualityScore() == null) {
                continue;
            }
            total = total.add(score.getQualityScore());
            scored++;
        }
        if (scored == 0) {
            return null;
        }
        return total.divide(BigDecimal.valueOf(scored), 2, RoundingMode.HALF_UP).doubleValue();
    }

    private static Long sumReach(List<DeliverableMetric> metrics) {
        long total = 0L;
        boolean any = false;
        for (DeliverableMetric m : metrics) {
            if (m.getReach() != null) {
                total += m.getReach();
                any = true;
            }
        }
        return any ? total : null;
    }

    private static BigDecimal sumRevenue(List<UtmCampaign> utmRows) {
        BigDecimal total = BigDecimal.ZERO;
        for (UtmCampaign u : utmRows) {
            if (u.getRevenueAttributed() != null) {
                total = total.add(u.getRevenueAttributed());
            }
        }
        return total;
    }

    private static BigDecimal sumCommission(List<AffiliateEarning> earnings) {
        BigDecimal total = BigDecimal.ZERO;
        for (AffiliateEarning e : earnings) {
            if (e.getCommissionAmount() != null) {
                total = total.add(e.getCommissionAmount());
            }
        }
        return total;
    }

    private static String stringArg(Map<String, Object> input, String key) {
        Object value = input == null ? null : input.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
