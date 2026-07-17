package com.influora.service;

import com.influora.common.ApiException;
import com.influora.common.ProofObjectKeys;
import com.influora.common.Ulids;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.DeliverableMetric;
import com.influora.domain.entity.PaymentMilestone;
import com.influora.domain.enums.MilestoneStatus;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.DeliverableMetricRepository;
import com.influora.repository.PaymentMilestoneRepository;
import com.influora.security.AuthPrincipal;
import com.influora.web.dto.analytics.AnalyticsDtos;
import com.influora.web.dto.analytics.AnalyticsDtos.CampaignAnalyticsResponse;
import com.influora.web.dto.analytics.AnalyticsDtos.DeliverableMetricResponse;
import com.influora.web.dto.analytics.AnalyticsDtos.DeliverableMetricSubmitRequest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creator-reported deliverable analytics (P0 #3, brand-audit backend build task — Q9). Ships
 * "reported-now": every number is self-declared by the creator. Verified platform-API
 * integration (Instagram/YouTube) is an explicit separate, later effort — nothing here fetches
 * from or reconciles against a platform API.
 *
 * <p><b>"Approved deliverable" gate:</b> there is no dedicated deliverable-approval entity yet in
 * this schema (only {@link PaymentMilestone}). A milestone is treated as reportable once the
 * brand has funded it ({@link MilestoneStatus#FUNDED} or {@link MilestoneStatus#RELEASED}) — that
 * is the closest existing signal that the brand has committed to (i.e. "approved") the
 * deliverable. Flagging for Priya: if a dedicated deliverable-submission/approval workflow is
 * built later, this gate should be swapped for that instead.
 */
@Service
public class DeliverableMetricService {

    private static final List<MilestoneStatus> REPORTABLE_STATUSES =
            List.of(MilestoneStatus.FUNDED, MilestoneStatus.RELEASED);

    private final DeliverableMetricRepository deliverableMetricRepository;
    private final PaymentMilestoneRepository milestoneRepository;
    private final CollaborationRepository collaborationRepository;
    private final CampaignRepository campaignRepository;
    private final BrandContextService brandContext;

    public DeliverableMetricService(
            DeliverableMetricRepository deliverableMetricRepository,
            PaymentMilestoneRepository milestoneRepository,
            CollaborationRepository collaborationRepository,
            CampaignRepository campaignRepository,
            BrandContextService brandContext) {
        this.deliverableMetricRepository = deliverableMetricRepository;
        this.milestoneRepository = milestoneRepository;
        this.collaborationRepository = collaborationRepository;
        this.campaignRepository = campaignRepository;
        this.brandContext = brandContext;
    }

    /**
     * Creator submits or updates their self-reported metrics for one deliverable (milestone).
     * Only the creator who owns the underlying collaboration may report on it.
     */
    @Transactional
    public DeliverableMetricResponse submit(
            AuthPrincipal principal, String milestoneId, DeliverableMetricSubmitRequest req) {
        PaymentMilestone milestone =
                milestoneRepository
                        .findById(milestoneId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "MILESTONE_NOT_FOUND", "Deliverable not found", HttpStatus.NOT_FOUND));

        Collaboration collaboration =
                collaborationRepository
                        .findById(milestone.getCollaborationId())
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "COLLABORATION_NOT_FOUND",
                                                "Collaboration not found",
                                                HttpStatus.NOT_FOUND));

        if (!collaboration.getCreatorId().equals(principal.getUserId())) {
            throw new ApiException(
                    "FORBIDDEN", "You may only report metrics on your own deliverables", HttpStatus.FORBIDDEN);
        }

        if (!REPORTABLE_STATUSES.contains(milestone.getStatus())) {
            throw new ApiException(
                    "DELIVERABLE_NOT_APPROVED",
                    "Metrics can only be reported once the deliverable's milestone is funded",
                    HttpStatus.CONFLICT);
        }

        validateNonNegative(req);

        String proofKey = req.proofScreenshotR2Key();
        if (proofKey != null && !proofKey.isBlank()) {
            if (!ProofObjectKeys.isOwnedByCreator(proofKey, principal.getUserId())) {
                throw new ApiException(
                        "INVALID_PROOF_KEY",
                        "Proof screenshot key is not owned by this creator",
                        HttpStatus.BAD_REQUEST);
            }
            proofKey = proofKey.trim();
        } else {
            proofKey = null;
        }

        DeliverableMetric metric =
                deliverableMetricRepository
                        .findByMilestoneId(milestoneId)
                        .orElseGet(
                                () ->
                                        DeliverableMetric.builder()
                                                .id(Ulids.newUlid())
                                                .milestoneId(milestoneId)
                                                .collaborationId(collaboration.getId())
                                                .reportedByCreatorId(principal.getUserId())
                                                .build());

        metric.applyReport(
                req.reach(),
                req.impressions(),
                req.engagements(),
                req.link(),
                proofKey,
                principal.getUserId());
        deliverableMetricRepository.save(metric);

        return toResponse(metric);
    }

    /**
     * Brand-facing aggregated analytics for a campaign: sums reach/impressions/engagements across
     * every reported deliverable and derives a simple engagement rate. Never backfills a missing
     * report with zero — {@code deliverablesReported} vs {@code deliverablesTotal} tells the
     * frontend how much of the picture is actually in, and an empty {@code deliverables} list
     * means "waiting for creator to report performance", not "zero performance".
     */
    @Transactional(readOnly = true)
    public CampaignAnalyticsResponse getCampaignAnalytics(
            AuthPrincipal principal, String workspaceId, String campaignId) {
        brandContext.requireMember(principal, workspaceId);

        Campaign campaign =
                campaignRepository
                        .findByIdAndWorkspaceId(campaignId, workspaceId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "CAMPAIGN_NOT_FOUND", "Campaign not found", HttpStatus.NOT_FOUND));

        List<Collaboration> collaborations = collaborationRepository.findByCampaignId(campaign.getId());
        List<String> collaborationIds = collaborations.stream().map(Collaboration::getId).toList();

        List<PaymentMilestone> milestones =
                collaborationIds.isEmpty()
                        ? List.of()
                        : milestoneRepository.findByCollaborationIdIn(collaborationIds);
        int deliverablesTotal =
                (int) milestones.stream().filter(m -> REPORTABLE_STATUSES.contains(m.getStatus())).count();

        List<DeliverableMetric> metrics =
                collaborationIds.isEmpty()
                        ? List.of()
                        : deliverableMetricRepository.findByCollaborationIdIn(collaborationIds);

        long totalReach = sumNullable(metrics, DeliverableMetric::getReach);
        long totalImpressions = sumNullable(metrics, DeliverableMetric::getImpressions);
        long totalEngagements = sumNullable(metrics, DeliverableMetric::getEngagements);

        BigDecimal derivedEngagementRate =
                totalImpressions > 0
                        ? BigDecimal.valueOf(totalEngagements)
                                .multiply(BigDecimal.valueOf(100))
                                .divide(BigDecimal.valueOf(totalImpressions), 2, RoundingMode.HALF_UP)
                        : null;

        List<DeliverableMetricResponse> deliverableResponses =
                metrics.stream().map(DeliverableMetricService::toResponse).toList();

        return new CampaignAnalyticsResponse(
                campaign.getId(),
                totalReach,
                totalImpressions,
                totalEngagements,
                derivedEngagementRate,
                metrics.size(),
                deliverablesTotal,
                com.influora.web.dto.analytics.AnalyticsDtos.SOURCE_CREATOR_REPORTED,
                deliverableResponses);
    }

    private static long sumNullable(
            List<DeliverableMetric> metrics, Function<DeliverableMetric, Long> getter) {
        return metrics.stream().map(getter).filter(v -> v != null).mapToLong(Long::longValue).sum();
    }

    private static void validateNonNegative(DeliverableMetricSubmitRequest req) {
        if (isNegative(req.reach()) || isNegative(req.impressions()) || isNegative(req.engagements())) {
            throw new ApiException(
                    "INVALID_METRIC_VALUE", "Reported metrics must be zero or positive", HttpStatus.BAD_REQUEST);
        }
    }

    private static boolean isNegative(Long value) {
        return value != null && value < 0;
    }

    private static DeliverableMetricResponse toResponse(DeliverableMetric metric) {
        return new DeliverableMetricResponse(
                metric.getId(),
                metric.getMilestoneId(),
                metric.getCollaborationId(),
                metric.getReach(),
                metric.getImpressions(),
                metric.getEngagements(),
                metric.getLink(),
                metric.getProofScreenshotR2Key(),
                metric.getReportedByCreatorId(),
                metric.getReportedAt(),
                // DPF-6: real per-row source (CREATOR_REPORTED or PLATFORM_VERIFIED), never
                // hardcoded — see DeliverableMetric/AnalyticsDtos class javadoc.
                metric.getSource());
    }
}
