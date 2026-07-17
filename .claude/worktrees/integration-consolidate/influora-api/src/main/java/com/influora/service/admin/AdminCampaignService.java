package com.influora.service.admin;

import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.Deliverable;
import com.influora.domain.entity.EscrowHold;
import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.AdminRole;
import com.influora.domain.enums.DeliverableStatus;
import com.influora.domain.enums.EscrowStatus;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.DeliverableRepository;
import com.influora.repository.EscrowHoldRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.security.AuthPrincipal;
import com.influora.web.dto.admin.AdminCampaignDtos.CampaignSummaryDto;
import java.math.BigDecimal;
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

    public AdminCampaignService(
            AdminContextService adminContextService,
            CampaignRepository campaignRepository,
            WorkspaceRepository workspaceRepository,
            CollaborationRepository collaborationRepository,
            EscrowHoldRepository escrowHoldRepository,
            DeliverableRepository deliverableRepository) {
        this.adminContextService = adminContextService;
        this.campaignRepository = campaignRepository;
        this.workspaceRepository = workspaceRepository;
        this.collaborationRepository = collaborationRepository;
        this.escrowHoldRepository = escrowHoldRepository;
        this.deliverableRepository = deliverableRepository;
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

        Map<String, String> workspaceNames = loadWorkspaceNames(campaigns);

        List<String> campaignIds = campaigns.stream().map(Campaign::getId).toList();

        // H-22: real per-campaign aggregates, batched for the whole page in 3 queries (not
        // 3*N) -- collaborations -> escrow holds (spend) and collaborations -> deliverables
        // (pending/approved/SLA breach) both hang off the collaboration, not the campaign
        // directly, so collaborations are fetched first and everything else groups off of it.
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

        List<CampaignSummaryDto> items =
                campaigns.stream()
                        .map(
                                c ->
                                        toSummaryDto(
                                                c,
                                                workspaceNames,
                                                collabsByCampaign.getOrDefault(c.getId(), List.of()),
                                                holdsByCampaign.getOrDefault(c.getId(), List.of()),
                                                deliverablesByCollaboration))
                        .collect(Collectors.toList());

        return new PagedCampaignSummaries(items, campaignPage.getTotalElements(), safePage, safePageSize);
    }

    public record PagedCampaignSummaries(
            List<CampaignSummaryDto> items, long totalElements, int page, int pageSize) {}

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
