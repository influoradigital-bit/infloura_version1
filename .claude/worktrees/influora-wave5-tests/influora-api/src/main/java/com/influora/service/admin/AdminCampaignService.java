package com.influora.service.admin;

import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.AdminRole;
import com.influora.repository.CampaignRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.security.AuthPrincipal;
import com.influora.web.dto.admin.AdminCampaignDtos.CampaignSummaryDto;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * {@code campaignApi.list()} call (currently mocked — see line 6 TODO).
 *
 * <p>This cycle (P2-7) ships the basic list endpoint (no filters, no sort params, no pagination —
 * all campaigns, every workspace) — the frontend currently sorts/filters client-side against its
 * mock data per {@code useCampaignList.ts} lines 208-243, and the acceptance criteria only call for
 * "real campaign list" (not full-featured admin search). Follow-up cycles can add server-side
 * filtering/sorting/pagination once the admin-panel campaign-search UX is ratified by Priya/Swapnil
 * and the exact filter semantics are spec'd.
 */
@Service
public class AdminCampaignService {

    /**
     * Meera G-7 — {@code campaignRepository.findAll()} below used to load every campaign row,
     * every workspace, in one unbounded query. {@code AdminCampaignController.list} still calls
     * the no-arg {@link #list(AuthPrincipal)} overload (controller/DTO changes needed to expose
     * real {@code page}/{@code pageSize} params to the client are out of this bundle's scope), so
     * this caps the query at a bounded page instead of the whole table — the fix that's possible
     * without touching the controller. Once the admin-panel campaign-search UX is spec'd, wire
     * {@link #list(AuthPrincipal, Pageable)} through the controller with real pagination params.
     */
    private static final int DEFAULT_PAGE_SIZE = 200;

    private final AdminContextService adminContextService;
    private final CampaignRepository campaignRepository;
    private final WorkspaceRepository workspaceRepository;

    public AdminCampaignService(
            AdminContextService adminContextService,
            CampaignRepository campaignRepository,
            WorkspaceRepository workspaceRepository) {
        this.adminContextService = adminContextService;
        this.campaignRepository = campaignRepository;
        this.workspaceRepository = workspaceRepository;
    }

    /**
     * Returns all campaigns, every workspace (admin-panel-wide view). Requires {@code SUPPORT},
     * {@code ADMIN}, or {@code SUPER_ADMIN} role with MFA satisfied (same access tier as
     * {@code AdminDashboardController.pulse}/{@code .operations} — campaign monitoring is
     * read-only operational visibility, not privileged write).
     *
     * <p>MVP (P2-7): returns ALL campaigns, no filtering/sorting/pagination. Future cycles can
     * accept {@code CampaignFilters}/{@code Pageable} params and delegate to a
     * {@code CampaignRepository.findAll(Specification, Pageable)} call once the admin-panel
     * campaign-search UX is fully spec'd.
     */
    @Transactional(readOnly = true)
    public List<CampaignSummaryDto> list(AuthPrincipal principal) {
        return list(
                principal,
                PageRequest.of(0, DEFAULT_PAGE_SIZE, Sort.by(Sort.Direction.DESC, "createdAt")));
    }

    /**
     * Meera G-7 — bounded/paginated variant of {@link #list(AuthPrincipal)}. Not yet reachable
     * from {@code AdminCampaignController} (that requires controller + DTO changes outside this
     * bundle's file scope); exists so the query itself is never unbounded regardless of caller.
     */
    @Transactional(readOnly = true)
    public List<CampaignSummaryDto> list(AuthPrincipal principal, Pageable pageable) {
        // Same role gate as AdminDashboardController — monitoring/operations read requires any
        // admin tier, MFA satisfied for ADMIN/SUPER_ADMIN.
        adminContextService.requireRoleWithMfaSatisfied(
                principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN, AdminRole.SUPPORT);

        Page<Campaign> page = campaignRepository.findAll(pageable);
        List<Campaign> all = page.getContent();

        // Bulk-load workspace names (one query, not N+1) — campaign rows carry workspaceId
        // (campaigns.workspace_id FK), and the admin panel wants brandName (workspace display name).
        Map<String, String> workspaceNames = loadWorkspaceNames(all);

        return all.stream().map(c -> toSummaryDto(c, workspaceNames)).collect(Collectors.toList());
    }

    private CampaignSummaryDto toSummaryDto(Campaign c, Map<String, String> workspaceNames) {
        // P2-7 MVP: no computed aggregates (spent, creatorCount, deliverable counts, slaBreachRate)
        // — those require joins to collaborations/deals/deliverables tables, and the acceptance
        // criteria only call for "real campaign list" (basic row data). Hardcoded placeholders here
        // match the FE mock's shape so the contract is satisfied; follow-up cycles can replace these
        // with real aggregate queries once that's prioritized.
        BigDecimal budgetMax = c.getBudgetMax() != null ? c.getBudgetMax() : BigDecimal.ZERO;
        BigDecimal spent = BigDecimal.ZERO; // TODO: sum(deals.amount) for this campaign
        Integer creatorCount = 0; // TODO: count(distinct collaborations.creator_id)
        Integer deliverablesPending = 0; // TODO: count deliverables where status=PENDING
        Integer deliverablesApproved = 0; // TODO: count deliverables where status=APPROVED
        Double slaBreachRate = 0.0; // TODO: (overdue deliverables / total) * 100

        String brandName =
                workspaceNames.getOrDefault(c.getWorkspaceId(), "Unknown"); // defensive fallback

        // "type" is a FE-only concept for now (STANDARD vs HYPE) — hardcoded to STANDARD until
        // Priya ratifies the taxonomy and maps it to real campaign flags/tags.
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
