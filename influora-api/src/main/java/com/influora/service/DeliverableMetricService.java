package com.influora.service;

import com.influora.common.ApiException;
import com.influora.common.ProofObjectKeys;
import com.influora.common.Ulids;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.Contract;
import com.influora.domain.entity.DeliverableMetric;
import com.influora.domain.entity.PaymentMilestone;
import com.influora.domain.enums.MilestoneStatus;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.ContractRepository;
import com.influora.repository.DeliverableMetricRepository;
import com.influora.repository.PaymentMilestoneRepository;
import com.influora.security.AuthPrincipal;
import com.influora.web.dto.analytics.AnalyticsDtos;
import com.influora.web.dto.analytics.AnalyticsDtos.CampaignAnalyticsResponse;
import com.influora.web.dto.analytics.AnalyticsDtos.DeliverableMetricResponse;
import com.influora.web.dto.analytics.AnalyticsDtos.DeliverableMetricSubmitRequest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
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
    private final ContractRepository contractRepository;
    private final BrandContextService brandContext;

    public DeliverableMetricService(
            DeliverableMetricRepository deliverableMetricRepository,
            PaymentMilestoneRepository milestoneRepository,
            CollaborationRepository collaborationRepository,
            CampaignRepository campaignRepository,
            ContractRepository contractRepository,
            BrandContextService brandContext) {
        this.deliverableMetricRepository = deliverableMetricRepository;
        this.milestoneRepository = milestoneRepository;
        this.collaborationRepository = collaborationRepository;
        this.campaignRepository = campaignRepository;
        this.contractRepository = contractRepository;
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

        List<PaymentMilestone> allMilestones =
                collaborationIds.isEmpty()
                        ? List.of()
                        : milestoneRepository.findByCollaborationIdIn(collaborationIds);

        // [F-0644, dropped-field / F-0653, conflicting-current-contract-definitions — fixed]
        // Amending a contract inserts a NEW Contract row and NEW PaymentMilestone rows for the
        // same collaboration, but leaves the superseded contract's milestone rows persisted
        // (collaboration-keyed, not contract-keyed — see ContractService#amend's javadoc, "What
        // happens to the row being superseded"). Counting every milestone for the collaboration
        // therefore double-counts across contract versions on every amendment.
        //
        // A previous attempt at this fix resolved "current" here with a bare
        // findByCollaborationIdOrderByVersionDescCreatedAtDesc(...).findFirst() — the newest
        // (version, createdAt) row, full stop. That silently disagreed with
        // DealService#toDealResponse's own "current contract" resolution for the SAME
        // collaboration whenever the newest row was an unsigned amendment draft superseding a
        // still-ACTIVE, funded predecessor: drafting an amendment made a RELEASED milestone's
        // metrics vanish from this aggregate the instant the draft existed, before anyone signed
        // anything. A fresh-context CTO review rejected that pass specifically for shipping two
        // independently-derived, disagreeing definitions of "current" in the same wave.
        //
        // Fixed by calling the SAME resolution DealService uses — literally the same method,
        // ContractService#resolveCurrentContract, not a second implementation that happens to
        // agree today. See that method's javadoc for the full rule (newest wins unless it's an
        // unsigned draft, in which case the still-ACTIVE predecessor is preferred) and
        // ContractService#retirePredecessorIfSuperseded for how the ACTIVE predecessor is
        // actually retired once the amendment is genuinely signed, so this converges back to the
        // amendment's own milestones at that point rather than staying pinned to the retired
        // predecessor's. The OLD milestone rows are left untouched either way (deleting/mutating
        // them would corrupt payment history), just excluded from this aggregate.
        //
        // [F-0657, n-plus-one-query — fixed] Resolving "current" used to call
        // ContractRepository#findByCollaborationIdOrderByVersionDescCreatedAtDesc once PER
        // collaboration inside this stream — one round trip per row of `collaborationIds`, so a
        // campaign with N collaborations issued N contract queries. Batched into the single
        // `findByWorkspaceId` call below (every collaboration here already belongs to `campaign`,
        // which belongs to `workspaceId`, so filtering that one result set down to
        // `collaborationIds` is exact, not an approximation) and grouped in memory; per-group
        // ordering is reproduced locally (version desc, createdAt desc — the same tie-break the
        // batched repository method itself documents) so resolveCurrentContract sees the identical
        // shape it always has. No new ContractRepository method was needed to do this within this
        // file's boundary; a dedicated `findByCollaborationIdIn` would be tighter (avoids pulling
        // in contracts from the workspace's OTHER campaigns) but that change lives in
        // ContractRepository, outside this pass's file scope.
        List<Contract> workspaceContracts =
                collaborationIds.isEmpty() ? List.of() : contractRepository.findByWorkspaceId(workspaceId);
        Map<String, List<Contract>> contractsByCollaborationId =
                workspaceContracts.stream()
                        .filter(c -> collaborationIds.contains(c.getCollaborationId()))
                        .collect(Collectors.groupingBy(Contract::getCollaborationId));

        Set<String> currentContractIds =
                collaborationIds.stream()
                        .flatMap(
                                id ->
                                        Stream.ofNullable(
                                                ContractService.resolveCurrentContract(
                                                        sortedByVersionThenCreatedAtDesc(
                                                                contractsByCollaborationId.getOrDefault(
                                                                        id, List.of())))))
                        .map(Contract::getId)
                        .collect(Collectors.toSet());

        // [F-0655, dropped-field — fixed] Once an amendment is signed,
        // ContractService#retirePredecessorIfSuperseded retires the predecessor to COMPLETED and
        // it drops out of `currentContractIds` entirely (see resolveCurrentContract above) — that
        // correctly stops its still-open FUNDED milestones from counting (they were superseded by
        // the amendment's renegotiated terms), but it ALSO silently dropped any RELEASED milestone
        // the predecessor had, even though RELEASED means the money was already paid out and the
        // deliverable already completed under that version — a historical fact the amendment does
        // not and cannot undo. A milestone that has actually been RELEASED must keep counting
        // regardless of which contract version it happens to sit on; only non-terminal
        // (FUNDED-but-not-yet-released) milestones are scoped to the current contract, since those
        // are the ones an amendment can still supersede/renegotiate away.
        List<PaymentMilestone> milestones =
                allMilestones.stream()
                        .filter(
                                m ->
                                        currentContractIds.contains(m.getContractId())
                                                || m.getStatus() == MilestoneStatus.RELEASED)
                        .toList();
        int deliverablesTotal =
                (int) milestones.stream().filter(m -> REPORTABLE_STATUSES.contains(m.getStatus())).count();

        Set<String> currentMilestoneIds =
                milestones.stream().map(PaymentMilestone::getId).collect(Collectors.toSet());

        List<DeliverableMetric> allMetrics =
                collaborationIds.isEmpty()
                        ? List.of()
                        : deliverableMetricRepository.findByCollaborationIdIn(collaborationIds);
        List<DeliverableMetric> metrics =
                allMetrics.stream()
                        .filter(dm -> currentMilestoneIds.contains(dm.getMilestoneId()))
                        .toList();

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

    /**
     * Reproduces, in memory, the exact ordering {@code
     * ContractRepository#findByCollaborationIdOrderByVersionDescCreatedAtDesc} would give for one
     * collaboration's contracts — version DESC, createdAt DESC as the tie-break — so batching that
     * query (see F-0657 note above) doesn't change what {@link
     * ContractService#resolveCurrentContract} sees.
     */
    private static List<Contract> sortedByVersionThenCreatedAtDesc(List<Contract> contracts) {
        return contracts.stream()
                .sorted(
                        Comparator.comparingInt(Contract::getVersion)
                                .thenComparing(Contract::getCreatedAt)
                                .reversed())
                .toList();
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
