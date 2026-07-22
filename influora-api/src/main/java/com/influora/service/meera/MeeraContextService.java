package com.influora.service.meera;

import com.influora.common.ApiException;
import com.influora.common.JsonLists;
import com.influora.domain.entity.BrandAiCredit;
import com.influora.domain.entity.BrandProfile;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.CampaignTemplate;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.DeliverableMetric;
import com.influora.domain.entity.UtmCampaign;
import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.CampaignStatus;
import com.influora.domain.enums.CampaignTemplateScope;
import com.influora.domain.enums.EscrowStatus;
import com.influora.repository.BrandProfileRepository;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CampaignTemplateRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.CollaborationRepository.RateBandCandidateRow;
import com.influora.repository.DeliverableMetricRepository;
import com.influora.repository.EscrowHoldRepository;
import com.influora.repository.UtmCampaignRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.web.dto.meera.MeeraContextDtos.ContextResponse;
import com.influora.web.dto.meera.MeeraContextDtos.OutcomeDigest;
import com.influora.web.dto.meera.MeeraContextDtos.PastCampaignEntry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final EscrowHoldRepository escrowHoldRepository;
    private final DeliverableMetricRepository deliverableMetricRepository;
    private final UtmCampaignRepository utmCampaignRepository;
    private final AICreditService creditService;
    private final BrandContextAssembler contextAssembler;

    public MeeraContextService(
            WorkspaceRepository workspaceRepository,
            BrandProfileRepository brandProfileRepository,
            CampaignTemplateRepository templateRepository,
            CampaignRepository campaignRepository,
            CollaborationRepository collaborationRepository,
            EscrowHoldRepository escrowHoldRepository,
            DeliverableMetricRepository deliverableMetricRepository,
            UtmCampaignRepository utmCampaignRepository,
            AICreditService creditService,
            BrandContextAssembler contextAssembler) {
        this.workspaceRepository = workspaceRepository;
        this.brandProfileRepository = brandProfileRepository;
        this.templateRepository = templateRepository;
        this.campaignRepository = campaignRepository;
        this.collaborationRepository = collaborationRepository;
        this.escrowHoldRepository = escrowHoldRepository;
        this.deliverableMetricRepository = deliverableMetricRepository;
        this.utmCampaignRepository = utmCampaignRepository;
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

        // Same last-N campaign list backs both past_campaign_summary (Phase 1) and the Phase 2
        // outcome_digest — one campaign-selection query, reused, per Priya's B1 lock
        // (assembleOutcomeDigest MUST reuse PAST_CAMPAIGN_LIMIT, never a separate/larger N).
        List<Campaign> recentCampaigns =
                campaignRepository.findByWorkspaceId(workspaceId).stream()
                        .sorted(Comparator.comparing(Campaign::getCreatedAt).reversed())
                        .limit(PAST_CAMPAIGN_LIMIT)
                        .toList();
        List<Collaboration> collaborations = collaborationRepository.findByWorkspaceId(workspaceId);

        List<PastCampaignEntry> pastCampaigns = buildPastCampaignSummary(recentCampaigns, collaborations);
        OutcomeDigest outcomeDigest = buildOutcomeDigest(recentCampaigns, collaborations, brandProfile);

        BrandAiCredit credit = creditService.getStatus(workspaceId);
        String creditMode = credit.isUnlimited(Instant.now()) ? "unlimited" : "metered";

        return contextAssembler.assembleBrandContext(
                workspace,
                brandProfile,
                templates,
                pastCampaigns,
                creditMode,
                credit.getCreditsRemaining(),
                outcomeDigest);
    }

    /** Last N campaigns for this workspace: type, distinct creator count (collaborations), funded y/n. */
    private List<PastCampaignEntry> buildPastCampaignSummary(
            List<Campaign> recentCampaigns, List<Collaboration> collaborations) {
        if (recentCampaigns.isEmpty()) {
            return List.of();
        }

        List<PastCampaignEntry> summary = new ArrayList<>();
        for (Campaign campaign : recentCampaigns) {
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

    /**
     * Fetches the raw rows {@link BrandContextAssembler#assembleOutcomeDigest} needs and hands
     * them over for shaping — this class decides WHAT to fetch (Phase 2 item 2.1), never reshapes
     * what leaves the allow-list gate itself (same split as every other field in this class).
     *
     * <p><b>SR-1 (Priya B4 / plan Risk Landmine #1):</b> spend/funded is read via {@link
     * EscrowHoldRepository#sumAmountByCampaignIdAndStatus} scoped strictly to {@link
     * EscrowStatus#RELEASED} — this method never touches {@link #FUNDED_STATUSES}, which stays
     * the Phase-1 {@code past_campaign_summary}-only proxy above.
     */
    private OutcomeDigest buildOutcomeDigest(
            List<Campaign> recentCampaigns, List<Collaboration> collaborations, BrandProfile brandProfile) {
        Map<String, BigDecimal> releasedSpendByCampaignId = new LinkedHashMap<>();
        Map<String, List<DeliverableMetric>> verifiedMetricsByCampaignId = new LinkedHashMap<>();
        Map<String, List<UtmCampaign>> utmByCampaignId = new LinkedHashMap<>();

        for (Campaign campaign : recentCampaigns) {
            String campaignId = campaign.getId();
            releasedSpendByCampaignId.put(
                    campaignId, escrowHoldRepository.sumAmountByCampaignIdAndStatus(campaignId, EscrowStatus.RELEASED));

            List<String> collaborationIds =
                    collaborations.stream()
                            .filter(c -> campaignId.equals(c.getCampaignId()))
                            .map(Collaboration::getId)
                            .toList();
            verifiedMetricsByCampaignId.put(
                    campaignId,
                    collaborationIds.isEmpty()
                            ? List.of()
                            : deliverableMetricRepository.findByCollaborationIdIn(collaborationIds));

            utmByCampaignId.put(campaignId, utmCampaignRepository.findByCampaignId(campaignId));
        }

        // niche_rate_band is a platform-wide aggregate for the brand's OWN niche — independent of
        // whether this workspace has any campaigns of its own yet.
        List<String> nicheTags =
                brandProfile != null ? JsonLists.stringListFromJson(brandProfile.getNicheTagsJson()) : List.of();
        String niche = nicheTags.isEmpty() ? null : nicheTags.get(0);
        List<RateBandCandidateRow> rateBandCandidates =
                niche == null ? List.of() : collaborationRepository.findRateBandCandidates(niche);

        return contextAssembler.assembleOutcomeDigest(
                recentCampaigns,
                collaborations,
                releasedSpendByCampaignId,
                verifiedMetricsByCampaignId,
                utmByCampaignId,
                niche,
                rateBandCandidates);
    }
}
