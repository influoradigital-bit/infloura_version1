package com.influora.service.meera;

import com.influora.common.ApiException;
import com.influora.domain.entity.BrandAiCredit;
import com.influora.domain.entity.BrandProfile;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.CampaignTemplate;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.CampaignStatus;
import com.influora.domain.enums.CampaignTemplateScope;
import com.influora.repository.BrandProfileRepository;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CampaignTemplateRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.web.dto.meera.MeeraContextDtos.ContextResponse;
import com.influora.web.dto.meera.MeeraContextDtos.PastCampaignEntry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrator for {@code POST /internal/meera/context} (Platform-AI Phase 1, Wave 1a — Priya A2).
 * Fetches the tenant-scoped rows the response needs (workspace, brand profile, templates, recent
 * campaigns, credit state) and hands them to {@link BrandContextAssembler} — the ONLY place the
 * A1 field allow-list is enforced (this class does no ad-hoc field selection of its own; it only
 * decides WHAT to fetch, never reshapes what leaves the allow-list gate).
 *
 * <p><b>CREATOR audience is Phase 3</b> (Priya A4) — this wave hardcodes/guards {@code
 * audience=BRAND} per Arjun's Wave-1 routing; a request for any other audience value is rejected
 * rather than silently returning an empty/wrong-shaped payload, so a future caller can never
 * mistake "not implemented yet" for "this workspace has no creator data".
 */
@Service
public class MeeraContextService {

    /** Last-N campaigns fed into {@code past_campaign_summary} — keeps the digest ~2-3 lines (Ash's cost note). */
    private static final int PAST_CAMPAIGN_LIMIT = 5;

    private static final String BRAND_AUDIENCE = "BRAND";

    /**
     * A campaign is treated as "funded" once it has left DRAFT/PENDING_APPROVAL — going ACTIVE is
     * the point escrow funds per the Commit-tier model (06-MEERA-PERMISSIONS-MATRIX.md: "going
     * live funds escrow = Commit (human)"). This is a pragmatic proxy, not a join against the
     * escrow table itself — flagged as an assumption for Kavya/Kabir to confirm against Domain A's
     * actual funding event once that lands.
     */
    private static final Set<CampaignStatus> FUNDED_STATUSES =
            EnumSet.of(CampaignStatus.ACTIVE, CampaignStatus.PAUSED, CampaignStatus.COMPLETED);

    private final WorkspaceRepository workspaceRepository;
    private final BrandProfileRepository brandProfileRepository;
    private final CampaignTemplateRepository templateRepository;
    private final CampaignRepository campaignRepository;
    private final CollaborationRepository collaborationRepository;
    private final AICreditService creditService;
    private final BrandContextAssembler contextAssembler;

    public MeeraContextService(
            WorkspaceRepository workspaceRepository,
            BrandProfileRepository brandProfileRepository,
            CampaignTemplateRepository templateRepository,
            CampaignRepository campaignRepository,
            CollaborationRepository collaborationRepository,
            AICreditService creditService,
            BrandContextAssembler contextAssembler) {
        this.workspaceRepository = workspaceRepository;
        this.brandProfileRepository = brandProfileRepository;
        this.templateRepository = templateRepository;
        this.campaignRepository = campaignRepository;
        this.collaborationRepository = collaborationRepository;
        this.creditService = creditService;
        this.contextAssembler = contextAssembler;
    }

    @Transactional(readOnly = true)
    public ContextResponse assemble(String workspaceId, String audience) {
        if (!BRAND_AUDIENCE.equalsIgnoreCase(audience)) {
            // A4: CREATOR allow-list is locked but Phase 3 — structurally guarded, never populated,
            // rather than silently returning an empty/wrong-shaped BRAND-less payload.
            throw new ApiException(
                    "AUDIENCE_NOT_SUPPORTED",
                    "audience '" + audience + "' is not yet supported (Phase 1 is BRAND-only)",
                    HttpStatus.BAD_REQUEST);
        }

        Workspace workspace =
                workspaceRepository
                        .findById(workspaceId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "WORKSPACE_NOT_FOUND", "Workspace not found", HttpStatus.NOT_FOUND));
        BrandProfile brandProfile = brandProfileRepository.findByWorkspaceId(workspaceId).orElse(null);

        List<CampaignTemplate> templates = new ArrayList<>();
        templates.addAll(templateRepository.findByScope(CampaignTemplateScope.SYSTEM));
        templates.addAll(templateRepository.findByScopeAndWorkspaceId(CampaignTemplateScope.CUSTOM, workspaceId));

        List<PastCampaignEntry> pastCampaigns = buildPastCampaignSummary(workspaceId);

        BrandAiCredit credit = creditService.getStatus(workspaceId);
        String creditMode = credit.isUnlimited(Instant.now()) ? "unlimited" : "metered";

        return contextAssembler.assembleBrandContext(
                workspace, brandProfile, templates, pastCampaigns, creditMode, credit.getCreditsRemaining());
    }

    /** Last N campaigns for this workspace: type, distinct creator count (collaborations), funded y/n. */
    private List<PastCampaignEntry> buildPastCampaignSummary(String workspaceId) {
        List<Campaign> recent =
                campaignRepository.findByWorkspaceId(workspaceId).stream()
                        .sorted(Comparator.comparing(Campaign::getCreatedAt).reversed())
                        .limit(PAST_CAMPAIGN_LIMIT)
                        .toList();
        if (recent.isEmpty()) {
            return List.of();
        }

        List<Collaboration> collaborations = collaborationRepository.findByWorkspaceId(workspaceId);

        List<PastCampaignEntry> summary = new ArrayList<>();
        for (Campaign campaign : recent) {
            long creatorCount =
                    collaborations.stream()
                            .filter(c -> campaign.getId().equals(c.getCampaignId()))
                            .map(Collaboration::getCreatorId)
                            .distinct()
                            .count();
            summary.add(
                    new PastCampaignEntry(
                            campaign.getCampaignType() != null ? campaign.getCampaignType().name() : "STANDARD",
                            (int) creatorCount,
                            FUNDED_STATUSES.contains(campaign.getStatus())));
        }
        return summary;
    }
}
