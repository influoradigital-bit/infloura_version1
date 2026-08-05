package com.influora.service.admin;

import com.influora.common.ApiException;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.Deliverable;
import com.influora.domain.entity.EscrowHold;
import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.AdminRole;
import com.influora.domain.enums.CollaborationStatus;
import com.influora.domain.enums.DeliverableStatus;
import com.influora.domain.enums.EscrowStatus;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.DeliverableRepository;
import com.influora.repository.EscrowHoldRepository;
import com.influora.repository.UserRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.security.AuthPrincipal;
import com.influora.web.dto.admin.AdminCampaignDtos.CampaignCreatorDto;
import com.influora.web.dto.admin.AdminCampaignDtos.CampaignDetailDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.influora.domain.enums.CampaignIntentType;
import com.influora.domain.enums.CampaignStatus;
import com.influora.web.dto.admin.AdminCampaignDtos.CampaignSummaryDto;
import com.influora.web.dto.admin.AdminCampaignDtos.HypeOpsDto;
import com.influora.web.dto.admin.AdminCampaignDtos.DeliverableRequirementDto;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin campaign-monitoring service (P2-7 — Vikram). Powers
 * {@code AdminCampaignController.list}, which backs {@code useCampaignList.ts}'s
 * {@code campaignApi.list()} call.
 *
 * <p><b>M-20/H-22 (this pass):</b> the previous version capped {@code campaignRepository.findAll()}
 * at a fixed 200-row page with no way for a caller to move past it (M-20 — "unpaginated
 * {@code findAll()}"), and every computed field on {@link CampaignSummaryDto} (spent, creatorCount,
 * deliverablesPending, deliverablesApproved, slaBreachRate) was a hardcoded 0/0.0 (H-22 — admins
 * were deciding off fabricated numbers). {@link #list(AuthPrincipal, int, int)} now accepts real
 * {@code page}/{@code pageSize} params (wired through {@code AdminCampaignController}, which also
 * now returns the true total row count via an {@code X-Total-Count} response header — the response
 * body stays a plain array to avoid a breaking contract change for the frontend, which today reads
 * {@code campaignApi.list()} as {@code CampaignSummary[]}), and every computed field is a real
 * grouped aggregate, batched for the whole page (not N+1 per row).
 */
@Service
public class AdminCampaignService {

    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 200;

    /** Mirrors {@code DealService}'s "cleared brand review" definition — reused here for slaBreachRate. */
    private static final Set<DeliverableStatus> DONE_DELIVERABLE_STATUSES =
            Set.of(
                    DeliverableStatus.APPROVED,
                    DeliverableStatus.POSTED,
                    DeliverableStatus.METRICS_REPORTED,
                    DeliverableStatus.VERIFIED);

    private final AdminContextService adminContextService;
    private final CampaignRepository campaignRepository;
    private final WorkspaceRepository workspaceRepository;
    private final CollaborationRepository collaborationRepository;
    private final EscrowHoldRepository escrowHoldRepository;
    private final DeliverableRepository deliverableRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    public AdminCampaignService(
            AdminContextService adminContextService,
            CampaignRepository campaignRepository,
            WorkspaceRepository workspaceRepository,
            CollaborationRepository collaborationRepository,
            EscrowHoldRepository escrowHoldRepository,
            DeliverableRepository deliverableRepository,
            UserRepository userRepository,
            ObjectMapper objectMapper) {
        this.adminContextService = adminContextService;
        this.campaignRepository = campaignRepository;
        this.workspaceRepository = workspaceRepository;
        this.collaborationRepository = collaborationRepository;
        this.escrowHoldRepository = escrowHoldRepository;
        this.deliverableRepository = deliverableRepository;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * {@code GET /admin/campaigns/hype/ops} — live operational snapshot of active HYPE campaigns.
     * Backs {@code campaignApi.getHypeOps()}. See {@link HypeOpsDto} for the exact per-field
     * derivation and the declared 60-minute {@code approvalsPerHour} window. Same role gate as
     * {@link #list}. Read-only; batched (never a per-campaign query).
     */
    @Transactional(readOnly = true)
    public HypeOpsDto hypeOps(AuthPrincipal principal) {
        adminContextService.requireRoleWithMfaSatisfied(
                principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN, AdminRole.SUPPORT);

        List<Campaign> hype =
                campaignRepository.findByCampaignTypeAndStatus(
                        CampaignIntentType.HYPE, CampaignStatus.ACTIVE);

        int totalSlots = 0;
        int filledSlots = 0;
        for (Campaign c : hype) {
            JsonNode cfg = parseHypeConfig(c.getHypeConfigJson());
            if (cfg != null) {
                totalSlots += cfg.path("slotCap").asInt(0);
                filledSlots += cfg.path("slotsFilled").asInt(0);
            }
        }
        double avgFillRate = totalSlots == 0 ? 0.0 : (double) filledSlots / totalSlots;

        // approvalsPerHour: deliverables in these active HYPE campaigns whose approvedAt is within
        // the last 60 minutes. approved_at is stamped exactly when a deliverable is APPROVED
        // (Deliverable.approve()), so this is a real count over a declared window.
        Instant cutoff = Instant.now().minus(Duration.ofHours(1));
        List<String> hypeCampaignIds = hype.stream().map(Campaign::getId).toList();
        List<Collaboration> collaborations =
                hypeCampaignIds.isEmpty()
                        ? List.of()
                        : collaborationRepository.findByCampaignIdIn(hypeCampaignIds);
        List<String> collaborationIds = collaborations.stream().map(Collaboration::getId).toList();
        List<Deliverable> deliverables =
                collaborationIds.isEmpty()
                        ? List.of()
                        : deliverableRepository.findByCollaborationIdIn(collaborationIds);
        int approvalsPerHour =
                (int)
                        deliverables.stream()
                                .filter(d -> d.getApprovedAt() != null && d.getApprovedAt().isAfter(cutoff))
                                .count();

        return new HypeOpsDto(hype.size(), totalSlots, filledSlots, avgFillRate, approvalsPerHour);
    }

    /**
     * Parses a campaign's {@code hype_config} JSON, returning {@code null} for a null/blank/malformed
     * value — a read must never throw on stored JSON it did not write. Callers treat {@code null} as
     * "no slots" (contributes 0), never a fabricated default.
     */
    private JsonNode parseHypeConfig(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    /** {@code page}/{@code pageSize} default to the first {@link #DEFAULT_PAGE_SIZE}-row page. */
    @Transactional(readOnly = true)
    public PagedCampaignSummaries list(AuthPrincipal principal) {
        return list(principal, 1, DEFAULT_PAGE_SIZE);
    }

    /**
     * Real pagination (M-20) over every campaign, every workspace (admin-panel-wide view).
     * Requires {@code SUPPORT}, {@code ADMIN}, or {@code SUPER_ADMIN} role with MFA satisfied
     * (same access tier as {@code AdminDashboardController.pulse}/{@code .operations}).
     */
    @Transactional(readOnly = true)
    public PagedCampaignSummaries list(AuthPrincipal principal, int page, int pageSize) {
        adminContextService.requireRoleWithMfaSatisfied(
                principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN, AdminRole.SUPPORT);

        int safePage = Math.max(page, 1);
        int safePageSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        Pageable pageable =
                PageRequest.of(safePage - 1, safePageSize, Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<Campaign> campaignPage = campaignRepository.findAll(pageable);
        List<Campaign> campaigns = campaignPage.getContent();

        List<CampaignSummaryDto> items = assembleSummaries(campaigns);

        return new PagedCampaignSummaries(items, campaignPage.getTotalElements(), safePage, safePageSize);
    }

    /**
     * Builds a {@link CampaignSummaryDto} for each campaign with all real per-campaign aggregates
     * (spend, creators, deliverable counts, SLA breach) — batched for the whole list in 3 queries
     * (not 3*N). Shared by {@link #list} and {@link #atRisk} so there is exactly one definition of
     * every computed field. Order of the input list is preserved.
     *
     * <p>H-22: collaborations -&gt; escrow holds (spend) and collaborations -&gt; deliverables
     * (pending/approved/SLA breach) both hang off the collaboration, not the campaign directly, so
     * collaborations are fetched first and everything else groups off of it.
     */
    private List<CampaignSummaryDto> assembleSummaries(List<Campaign> campaigns) {
        Map<String, String> workspaceNames = loadWorkspaceNames(campaigns);

        List<String> campaignIds = campaigns.stream().map(Campaign::getId).toList();

        List<Collaboration> collaborations =
                campaignIds.isEmpty() ? List.of() : collaborationRepository.findByCampaignIdIn(campaignIds);
        Map<String, List<Collaboration>> collabsByCampaign =
                collaborations.stream().collect(Collectors.groupingBy(Collaboration::getCampaignId));

        List<EscrowHold> escrowHolds =
                campaignIds.isEmpty() ? List.of() : escrowHoldRepository.findByCampaignIdIn(campaignIds);
        Map<String, List<EscrowHold>> holdsByCampaign =
                escrowHolds.stream().collect(Collectors.groupingBy(EscrowHold::getCampaignId));

        List<String> collaborationIds = collaborations.stream().map(Collaboration::getId).toList();
        List<Deliverable> deliverables =
                collaborationIds.isEmpty() ? List.of() : deliverableRepository.findByCollaborationIdIn(collaborationIds);
        Map<String, List<Deliverable>> deliverablesByCollaboration =
                deliverables.stream().collect(Collectors.groupingBy(Deliverable::getCollaborationId));

        return campaigns.stream()
                .map(
                        c ->
                                toSummaryDto(
                                        c,
                                        workspaceNames,
                                        collabsByCampaign.getOrDefault(c.getId(), List.of()),
                                        holdsByCampaign.getOrDefault(c.getId(), List.of()),
                                        deliverablesByCollaboration))
                .collect(Collectors.toList());
    }

    /**
     * {@code GET /admin/campaigns/at-risk} — {@code ACTIVE} campaigns with a live SLA breach. Backs
     * {@code campaignApi.getAtRisk()} ({@code CampaignSummary[]}, unwrapped). Same role gate and the
     * same real aggregation ({@link #assembleSummaries}) as {@link #list}.
     *
     * <p><b>Declared working definition (NOT yet product-ratified):</b> the formal at-risk SLA rule
     * is deferred (see {@link #getById}'s {@code timelineStatus} note). Until product ratifies a
     * threshold, "at-risk" here means an {@code ACTIVE} campaign whose live {@code slaBreachRate}
     * &gt; 0 — at least one deliverable is past its deadline and not yet done, computed from real
     * deliverable deadlines in {@link #toSummaryDto}, never a placeholder. Only the threshold
     * ({@code &gt; 0}) is a working choice product may tighten; the underlying number is real.
     */
    @Transactional(readOnly = true)
    public List<CampaignSummaryDto> atRisk(AuthPrincipal principal) {
        adminContextService.requireRoleWithMfaSatisfied(
                principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN, AdminRole.SUPPORT);

        List<Campaign> active = campaignRepository.findByStatus(CampaignStatus.ACTIVE);
        return assembleSummaries(active).stream()
                .filter(dto -> dto.slaBreachRate() != null && dto.slaBreachRate() > 0.0)
                .toList();
    }

    public record PagedCampaignSummaries(
            List<CampaignSummaryDto> items, long totalElements, int page, int pageSize) {}

    /**
     * Single-campaign detail view backing {@code AdminCampaignController.getById} / {@code
     * campaignApi.getById} (api-contracts.ts:313-314). Same role gate as {@link #list}
     * (SUPPORT/ADMIN/SUPER_ADMIN, MFA satisfied for ADMIN+).
     *
     * <p>{@code brief} maps from {@code Campaign.description} — the only free-text campaign-intent
     * field on the entity. {@code deliverables} is every {@link Deliverable} row across the
     * campaign's collaborations (lean per-slot rows, not grouped by type — see {@code
     * DeliverableRequirementDto} javadoc). {@code creators} is one row per {@link Collaboration},
     * with {@code creatorName} resolved via {@link UserRepository} (collaborations.creator_id is a
     * plain {@code users.id} FK, same resolution {@code AdminSupportService.resolveUserName} uses
     * for ticket requesters) and {@code status} collapsed from {@link CollaborationStatus}'s 13
     * values down to the frontend's 5-value union (see {@link #mapCollaborationStatus}).
     * {@code escrowBalance} is FUNDED-only holds (money currently locked, not yet released) —
     * deliberately a different definition than this class's own {@code spent} (FUNDED+RELEASED),
     * matching the {@code escrowFloat} vs. {@code gmv} distinction {@code
     * AdminDashboardStatsCache} already draws.
     *
     * <p><b>DEFERRED: needs at-risk SLA definition.</b> {@code timelineStatus} is hardcoded {@code
     * "ON_TRACK"} — {@code getAtRisk}/{@code getHypeOps} and any real AT_RISK/DELAYED computation
     * are out of scope this pass per product decision; not built here.
     */
    @Transactional(readOnly = true)
    public CampaignDetailDto getById(AuthPrincipal principal, String campaignId) {
        adminContextService.requireRoleWithMfaSatisfied(
                principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN, AdminRole.SUPPORT);

        Campaign campaign =
                campaignRepository
                        .findById(campaignId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "CAMPAIGN_NOT_FOUND", "Campaign not found", HttpStatus.NOT_FOUND));

        String brandName =
                workspaceRepository.findById(campaign.getWorkspaceId()).map(Workspace::getName).orElse("Unknown");

        List<Collaboration> collaborations = collaborationRepository.findByCampaignId(campaignId);
        List<String> collaborationIds = collaborations.stream().map(Collaboration::getId).toList();
        List<Deliverable> deliverables =
                collaborationIds.isEmpty()
                        ? List.of()
                        : deliverableRepository.findByCollaborationIdIn(collaborationIds);
        List<EscrowHold> escrowHolds = escrowHoldRepository.findByCampaignId(campaignId);

        BigDecimal budgetMax = campaign.getBudgetMax() != null ? campaign.getBudgetMax() : BigDecimal.ZERO;

        // Same FUNDED/RELEASED "committed campaign spend" definition used by list()/toSummaryDto.
        BigDecimal spent =
                escrowHolds.stream()
                        .filter(h -> h.getStatus() == EscrowStatus.FUNDED || h.getStatus() == EscrowStatus.RELEASED)
                        .map(EscrowHold::getAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Locked-only -- see method javadoc's escrowFloat/gmv distinction.
        BigDecimal escrowBalance =
                escrowHolds.stream()
                        .filter(h -> h.getStatus() == EscrowStatus.FUNDED)
                        .map(EscrowHold::getAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

        int creatorCount = (int) collaborations.stream().map(Collaboration::getCreatorId).distinct().count();

        int deliverablesPending =
                (int) deliverables.stream().filter(d -> d.getStatus() == DeliverableStatus.PENDING).count();
        int deliverablesApproved =
                (int) deliverables.stream().filter(d -> d.getStatus() == DeliverableStatus.APPROVED).count();

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        long overdueCount =
                deliverables.stream()
                        .filter(d -> d.getDeadline() != null && d.getDeadline().isBefore(today))
                        .filter(d -> !DONE_DELIVERABLE_STATUSES.contains(d.getStatus()))
                        .count();
        Double slaBreachRate = deliverables.isEmpty() ? 0.0 : (overdueCount * 100.0) / deliverables.size();

        List<DeliverableRequirementDto> deliverableDtos =
                deliverables.stream()
                        .map(
                                d ->
                                        new DeliverableRequirementDto(
                                                d.getId(),
                                                d.getType().name(),
                                                d.getDescription() != null ? d.getDescription() : d.getTitle(),
                                                1,
                                                d.getDeadline()))
                        .toList();

        List<CampaignCreatorDto> creatorDtos =
                collaborations.stream()
                        .map(
                                c ->
                                        new CampaignCreatorDto(
                                                c.getCreatorId(),
                                                resolveCreatorName(c.getCreatorId()),
                                                mapCollaborationStatus(c.getStatus()),
                                                c.getAgreedRate() != null ? c.getAgreedRate() : BigDecimal.ZERO))
                        .toList();

        // "type" hardcode -- same not-yet-ratified STANDARD/HYPE taxonomy note as toSummaryDto.
        String type = "STANDARD";

        return new CampaignDetailDto(
                campaign.getId(),
                campaign.getTitle(),
                brandName,
                type,
                campaign.getStatus(),
                budgetMax,
                spent,
                creatorCount,
                deliverablesPending,
                deliverablesApproved,
                slaBreachRate,
                campaign.getCreatedAt(),
                campaign.getEndDate(),
                campaign.getDescription(),
                deliverableDtos,
                creatorDtos,
                escrowBalance,
                // DEFERRED: needs at-risk SLA definition -- hardcoded until product ratifies it.
                "ON_TRACK");
    }

    private String resolveCreatorName(String creatorUserId) {
        if (creatorUserId == null) {
            return null;
        }
        return userRepository
                .findById(creatorUserId)
                .map(u -> u.getDisplayName() != null ? u.getDisplayName() : u.getEmail())
                .orElse(null);
    }

    /**
     * Collapses {@link CollaborationStatus}'s 13 domain values down to {@code CampaignCreator}'s
     * 5-value FE union (admin.types.ts) -- {@code CANCELLED}/{@code DISPUTED} have no matching
     * bucket in that union at all (added before cancellation/dispute states existed here), mapped
     * to {@code COMPLETED} as the closest "no longer active" bucket rather than inventing a 6th
     * status value the FE doesn't expect. An honest approximation, not a fabricated value --
     * flagged as a gap for product/Priya to resolve if it matters for the UI.
     */
    private static String mapCollaborationStatus(CollaborationStatus status) {
        return switch (status) {
            case INVITED, APPLIED, SHORTLISTED -> "INVITED";
            case IN_NEGOTIATION, TERMS_AGREED -> "NEGOTIATING";
            case CONTRACT_PENDING, CONTRACTED -> "CONTRACTED";
            case IN_PROGRESS, REVIEW_PENDING, REVISION_REQUESTED -> "DELIVERING";
            case COMPLETED, CANCELLED, DISPUTED -> "COMPLETED";
        };
    }

    private CampaignSummaryDto toSummaryDto(
            Campaign c,
            Map<String, String> workspaceNames,
            List<Collaboration> campaignCollaborations,
            List<EscrowHold> campaignEscrowHolds,
            Map<String, List<Deliverable>> deliverablesByCollaboration) {
        BigDecimal budgetMax = c.getBudgetMax() != null ? c.getBudgetMax() : BigDecimal.ZERO;

        // Same FUNDED/RELEASED "committed campaign spend" definition AdminBrandService already
        // uses for its own per-brand/per-campaign spend figures -- one definition of "spend",
        // not two.
        BigDecimal spent =
                campaignEscrowHolds.stream()
                        .filter(h -> h.getStatus() == EscrowStatus.FUNDED || h.getStatus() == EscrowStatus.RELEASED)
                        .map(EscrowHold::getAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

        int creatorCount = (int) campaignCollaborations.stream().map(Collaboration::getCreatorId).distinct().count();

        List<Deliverable> campaignDeliverables =
                campaignCollaborations.stream()
                        .flatMap(
                                collab ->
                                        deliverablesByCollaboration
                                                .getOrDefault(collab.getId(), List.of())
                                                .stream())
                        .toList();

        int deliverablesPending =
                (int)
                        campaignDeliverables.stream()
                                .filter(d -> d.getStatus() == DeliverableStatus.PENDING)
                                .count();
        int deliverablesApproved =
                (int)
                        campaignDeliverables.stream()
                                .filter(d -> d.getStatus() == DeliverableStatus.APPROVED)
                                .count();

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        long overdueCount =
                campaignDeliverables.stream()
                        .filter(d -> d.getDeadline() != null && d.getDeadline().isBefore(today))
                        .filter(d -> !DONE_DELIVERABLE_STATUSES.contains(d.getStatus()))
                        .count();
        Double slaBreachRate =
                campaignDeliverables.isEmpty()
                        ? 0.0
                        : (overdueCount * 100.0) / campaignDeliverables.size();

        String brandName =
                workspaceNames.getOrDefault(c.getWorkspaceId(), "Unknown"); // defensive fallback

        // "type" is a FE-only concept for now (STANDARD vs HYPE) — hardcoded to STANDARD until
        // Priya ratifies the taxonomy and maps it to real campaign flags/tags. Not an H-22
        // fabricated-number concern (it's a display label, not a metric an admin decides money on).
        String type = "STANDARD";

        return new CampaignSummaryDto(
                c.getId(),
                c.getTitle(),
                brandName,
                type,
                c.getStatus(),
                budgetMax,
                spent,
                creatorCount,
                deliverablesPending,
                deliverablesApproved,
                slaBreachRate,
                c.getCreatedAt(),
                c.getEndDate());
    }

    private Map<String, String> loadWorkspaceNames(List<Campaign> campaigns) {
        // Extract unique workspace IDs, fetch Workspace rows in one query, return id->name map.
        List<String> workspaceIds =
                campaigns.stream().map(Campaign::getWorkspaceId).distinct().collect(Collectors.toList());
        if (workspaceIds.isEmpty()) {
            return Map.of();
        }
        List<Workspace> workspaces = workspaceRepository.findAllById(workspaceIds);
        Map<String, String> names = new HashMap<>();
        for (Workspace w : workspaces) {
            names.put(w.getId(), w.getName());
        }
        return names;
    }
}
