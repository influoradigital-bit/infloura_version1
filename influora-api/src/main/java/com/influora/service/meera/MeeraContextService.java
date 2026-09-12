package com.influora.service.meera;

import com.influora.common.ApiException;
import com.influora.common.JsonLists;
import com.influora.domain.entity.BrandAiCredit;
import com.influora.domain.entity.BrandProfile;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.CampaignTemplate;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.CreatorAgentPreferences;
import com.influora.domain.entity.CreatorMetric;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.DeliverableMetric;
import com.influora.domain.entity.UtmCampaign;
import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.CampaignStatus;
import com.influora.domain.enums.CampaignTemplateScope;
import com.influora.domain.enums.CollaborationStatus;
import com.influora.domain.enums.EscrowStatus;
import com.influora.domain.enums.VerificationStatus;
import com.influora.repository.BrandProfileRepository;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CampaignTemplateRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.CollaborationRepository.RateBandCandidateRow;
import com.influora.repository.CreatorAgentPreferencesRepository;
import com.influora.repository.CreatorMetricsRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.DeliverableMetricRepository;
import com.influora.repository.EscrowHoldRepository;
import com.influora.repository.UtmCampaignRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.web.dto.meera.MeeraContextDtos.ContextResponse;
import com.influora.web.dto.meera.MeeraContextDtos.CreatorContextResponse;
import com.influora.web.dto.meera.MeeraContextDtos.OutcomeDigest;
import com.influora.web.dto.meera.MeeraContextDtos.PastCampaignEntry;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
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

    /** T-MEERA-CREATOR-PHASE-A (SPEC.md 2.9, A4). */
    private static final String CREATOR_AUDIENCE = "CREATOR";

    /**
     * A campaign is treated as "funded" once it has left DRAFT/PENDING_APPROVAL — going ACTIVE is
     * the point escrow funds per the Commit-tier model (06-MEERA-PERMISSIONS-MATRIX.md: "going
     * live funds escrow = Commit (human)"). This is a pragmatic proxy, not a join against the
     * escrow table itself — flagged as an assumption for Kavya/Kabir to confirm against Domain A's
     * actual funding event once that lands.
     */
    private static final Set<CampaignStatus> FUNDED_STATUSES =
            EnumSet.of(CampaignStatus.ACTIVE, CampaignStatus.PAUSED, CampaignStatus.COMPLETED);

    /**
     * T-MEERA-CREATOR-PHASE-A (SPEC.md 2.9, A4) — {@code deals_summary.active_count}: every
     * non-terminal collaboration status. Terminal = {@code COMPLETED}/{@code CANCELLED}/{@code
     * DISPUTED}; everything else is still an open negotiation or in-flight deal from the
     * creator's point of view.
     */
    private static final Set<CollaborationStatus> ACTIVE_DEAL_STATUSES =
            EnumSet.complementOf(
                    EnumSet.of(
                            CollaborationStatus.COMPLETED, CollaborationStatus.CANCELLED, CollaborationStatus.DISPUTED));

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
    private final CreatorProfileRepository creatorProfileRepository;
    private final CreatorAgentPreferencesRepository creatorAgentPreferencesRepository;
    private final CreatorMetricsRepository creatorMetricsRepository;

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
            BrandContextAssembler contextAssembler,
            CreatorProfileRepository creatorProfileRepository,
            CreatorAgentPreferencesRepository creatorAgentPreferencesRepository,
            CreatorMetricsRepository creatorMetricsRepository) {
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
        this.creatorProfileRepository = creatorProfileRepository;
        this.creatorAgentPreferencesRepository = creatorAgentPreferencesRepository;
        this.creatorMetricsRepository = creatorMetricsRepository;
    }

    /**
     * T-MEERA-CREATOR-PHASE-A (SPEC.md 2.9, A4) — {@code audience} now branches to either the
     * BRAND path (unchanged below) or {@link #assembleCreatorContext}. Any other value is still
     * rejected outright rather than silently returning an empty/wrong-shaped payload.
     *
     * <p>Overloaded so existing BRAND callers keep their {@link ContextResponse}-typed call site;
     * {@code MeeraInternalController#context} calls this {@code Object}-returning overload and
     * relies on Jackson to serialize whichever concrete record comes back.
     */
    @Transactional(readOnly = true)
    public Object assemble(String workspaceId, String audience) {
        if (CREATOR_AUDIENCE.equalsIgnoreCase(audience)) {
            return assembleCreatorContext(workspaceId);
        }
        return assembleBrand(workspaceId, audience);
    }

    private ContextResponse assembleBrand(String workspaceId, String audience) {
        if (!BRAND_AUDIENCE.equalsIgnoreCase(audience)) {
            throw new ApiException(
                    "AUDIENCE_NOT_SUPPORTED",
                    "audience '" + audience + "' is not supported",
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

    /**
     * T-MEERA-CREATOR-PHASE-A (SPEC.md 2.9, A4) — {@code workspaceId} here is actually the
     * creator's USER id (the spec's own {@code "workspace_id": "creator_user_id_here"} comment on
     * the request shape) — the field is reused across audiences rather than renamed on the wire
     * contract Python already sends. Resolved to a {@link CreatorProfile} first; every other
     * lookup below is keyed off {@code profile.getId()} (the FK space {@code
     * creator_agent_preferences.creator_id} actually lives in), never the raw user id again.
     *
     * <p><b>Info barrier (A7):</b> this is the ONLY place {@link
     * CreatorAgentPreferencesRepository} may be read for a CREATOR turn's own floors — never
     * exposed to a BRAND context (see {@link #assembleBrand} above, which never touches this
     * repository). {@code identity} carries ONLY the two allow-listed booleans; PAN/GSTIN
     * value/Aadhaar never leave {@link CreatorProfile} through this method.
     */
    private CreatorContextResponse assembleCreatorContext(String creatorUserId) {
        CreatorProfile profile =
                creatorProfileRepository
                        .findByUserId(creatorUserId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "CREATOR_PROFILE_NOT_FOUND", "Creator profile not found", HttpStatus.NOT_FOUND));

        CreatorAgentPreferences prefs =
                creatorAgentPreferencesRepository.findByCreatorId(profile.getId()).orElse(null);

        String creatorLanguage =
                prefs != null && prefs.getCreatorLanguage() != null
                        ? prefs.getCreatorLanguage()
                        : firstLanguageOrDefault(profile);
        Locale locale = localeForLanguageTag(creatorLanguage);

        Map<String, String> floors = new LinkedHashMap<>();
        if (prefs != null) {
            putIfPresent(floors, "reel_floor", prefs.getReelFloor(), locale);
            putIfPresent(floors, "story_set_floor", prefs.getStorySetFloor(), locale);
            putIfPresent(floors, "post_floor", prefs.getPostFloor(), locale);
        }

        Optional<CreatorMetric> latestMetric =
                creatorMetricsRepository
                        .findByCreatorProfileIdOrderByTimeDesc(profile.getId(), PageRequest.of(0, 1))
                        .stream()
                        .findFirst();
        Map<String, String> metricsSummary = buildMetricsSummary(profile, latestMetric, locale);

        List<Collaboration> collaborations = collaborationRepository.findByCreatorId(creatorUserId);
        Map<String, Object> dealsSummary = buildDealsSummary(collaborations, locale);

        Map<String, Boolean> identity = new LinkedHashMap<>();
        identity.put("kyc_done", profile.getIdentityKycStatus() == VerificationStatus.VERIFIED);
        identity.put("gstin_present", profile.getGstin() != null && !profile.getGstin().isBlank());

        String tier = profile.getTierOverride() != null ? profile.getTierOverride().name() : deriveTier(profile.getTotalFollowers());

        // Fix round 2, item 3 (Priya Q8) — these were persisted correctly on the settings row but
        // never reached this response, so Meera never actually saw them. JsonLists.stringListFromJson
        // already returns an empty (never null) list for a null/blank JSON column.
        List<String> excludedCategories =
                prefs != null ? JsonLists.stringListFromJson(prefs.getExcludedCategoriesJson()) : List.of();
        List<String> blockedBrands =
                prefs != null ? JsonLists.stringListFromJson(prefs.getBlockedBrandsJson()) : List.of();
        List<Integer> workingDays =
                prefs != null
                        ? JsonLists.stringListFromJson(prefs.getWorkingDaysJson()).stream()
                                .map(Integer::parseInt)
                                .toList()
                        : List.of();

        return new CreatorContextResponse(
                creatorUserId,
                CREATOR_AUDIENCE,
                profile.getDisplayName(),
                firstNameOf(profile.getDisplayName()),
                profile.getCity(),
                tier,
                JsonLists.stringListFromJson(profile.getCategoriesJson()),
                creatorLanguage,
                prefs != null && prefs.getBrandTone() != null ? prefs.getBrandTone() : CreatorAgentPreferences.TONE_FRIENDLY,
                floors,
                metricsSummary,
                dealsSummary,
                prefs != null ? prefs.getApprovalLevel() : CreatorAgentPreferences.APPROVAL_LEVEL_DRAFT_ONLY,
                prefs != null && prefs.isRepresented(),
                prefs != null ? prefs.getAgencyName() : null,
                excludedCategories,
                blockedBrands,
                prefs != null ? prefs.getWorkingHoursStart() : null,
                prefs != null ? prefs.getWorkingHoursEnd() : null,
                prefs != null ? prefs.getWorkingHoursTimezone() : CreatorAgentPreferences.DEFAULT_WORKING_HOURS_TIMEZONE,
                workingDays,
                prefs != null ? prefs.getWeeklySponsoredLimit() : null,
                prefs != null ? prefs.getFloorCurrency() : CreatorAgentPreferences.DEFAULT_FLOOR_CURRENCY,
                identity,
                prefs != null && prefs.isConsentAccepted(),
                prefs != null ? prefs.getConsentVersion() : null,
                prefs != null ? formatCapUsd(prefs.getAiMonthlyCapUsd(), locale) : null);
    }

    /**
     * Gate fix round 1 (Priya Q7) — deliberately NOT {@link #formatDecimal} (which uses {@code
     * NumberFormat.getIntegerInstance}, rounding a cap like 0.75 down to "1"). A USD cap needs its
     * cents preserved so influora-ai's {@code Decimal(str(...))} parse gets the real value.
     */
    private static String formatCapUsd(BigDecimal capUsd, Locale locale) {
        if (capUsd == null) {
            return null;
        }
        NumberFormat format = NumberFormat.getNumberInstance(locale);
        format.setGroupingUsed(true);
        format.setMinimumFractionDigits(2);
        format.setMaximumFractionDigits(2);
        return format.format(capUsd);
    }

    /** A8 — every number the CREATOR context carries leaves this class as a locale-formatted string, never a raw numeric type. */
    private static Locale localeForLanguageTag(String bcp47) {
        try {
            return bcp47 != null ? Locale.forLanguageTag(bcp47) : Locale.forLanguageTag(CreatorAgentPreferences.DEFAULT_LANGUAGE);
        } catch (RuntimeException e) {
            return Locale.forLanguageTag(CreatorAgentPreferences.DEFAULT_LANGUAGE);
        }
    }

    private static void putIfPresent(Map<String, String> target, String key, BigDecimal value, Locale locale) {
        if (value != null) {
            target.put(key, formatDecimal(value, locale));
        }
    }

    private static String formatDecimal(BigDecimal value, Locale locale) {
        NumberFormat format = NumberFormat.getIntegerInstance(locale);
        format.setGroupingUsed(true);
        return format.format(value);
    }

    /**
     * Gate fix round 1 (Priya Q6/Q9.6) — a creator with no {@link CreatorMetric} row (never
     * connected Instagram, or a stale/expired token) previously always got a "followers" key,
     * either the Meta-verified count or a silent fallback to {@link CreatorProfile#totalFollowers}
     * (self-reported at onboarding, 0 for a brand-new creator). Because the key was always
     * present, assembler.py's honest "Instagram not connected yet (no verified numbers)" branch
     * (only triggered when {@code metrics_summary} carries no "followers" key) could never fire,
     * so Meera stated either a fabricated "0 followers" or an unverified self-reported number as
     * if it were a Meta-verified fact. Now: a verified {@link CreatorMetric} row formats normally;
     * absent that, a nonzero self-reported total is labelled as such; absent both, the key is
     * omitted entirely so the honest branch downstream fires.
     */
    private static Map<String, String> buildMetricsSummary(
            CreatorProfile profile, Optional<CreatorMetric> latestMetric, Locale locale) {
        Map<String, String> summary = new LinkedHashMap<>();
        if (latestMetric.isPresent()) {
            long followers = latestMetric.get().getFollowers();
            summary.put("followers", formatDecimal(BigDecimal.valueOf(followers), locale) + " followers");
        } else if (profile.getTotalFollowers() > 0) {
            summary.put(
                    "followers",
                    formatDecimal(BigDecimal.valueOf(profile.getTotalFollowers()), locale)
                            + " followers (self-reported, not verified)");
        }
        // else: no verified metric and no self-reported total — omit the key so the "Instagram
        // not connected yet" honest-state branch fires downstream instead of a fabricated zero.

        Long reach = latestMetric.map(CreatorMetric::getAvgReachPerPost).orElse(null);
        if (reach != null) {
            summary.put("reach_30d", formatDecimal(BigDecimal.valueOf(reach), locale) + " reach (30 days)");
        }

        BigDecimal engagement = latestMetric.map(CreatorMetric::getAvgEngagementRate).orElse(profile.getEngagementRate());
        if (engagement != null) {
            NumberFormat pctFormat = NumberFormat.getNumberInstance(locale);
            pctFormat.setMaximumFractionDigits(1);
            summary.put("engagement_rate", pctFormat.format(engagement) + "% engagement");
        }
        return summary;
    }

    private static Map<String, Object> buildDealsSummary(List<Collaboration> collaborations, Locale locale) {
        long activeCount =
                collaborations.stream().filter(c -> ACTIVE_DEAL_STATUSES.contains(c.getStatus())).count();
        long completedCount =
                collaborations.stream().filter(c -> c.getStatus() == CollaborationStatus.COMPLETED).count();
        BigDecimal totalEarned =
                collaborations.stream()
                        .filter(c -> c.getStatus() == CollaborationStatus.COMPLETED && c.getAgreedRate() != null)
                        .map(Collaboration::getAgreedRate)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("active_count", (int) activeCount);
        summary.put("completed_count", (int) completedCount);
        summary.put("total_earned_inr", formatDecimal(totalEarned, locale));
        return summary;
    }

    private static String firstLanguageOrDefault(CreatorProfile profile) {
        List<String> languages = JsonLists.stringListFromJson(profile.getLanguagesJson());
        return languages.isEmpty() ? CreatorAgentPreferences.DEFAULT_LANGUAGE : languages.get(0);
    }

    private static String firstNameOf(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            return displayName;
        }
        String trimmed = displayName.trim();
        int spaceIndex = trimmed.indexOf(' ');
        return spaceIndex > 0 ? trimmed.substring(0, spaceIndex) : trimmed;
    }

    /** Mirrors {@code CreatorAgentBaselineService.deriveTier} — kept as a private copy rather than a shared util for one three-line method. */
    private static String deriveTier(long followers) {
        if (followers >= 1_000_000) return "MEGA";
        if (followers >= 500_000) return "MACRO";
        if (followers >= 50_000) return "MID";
        if (followers >= 10_000) return "MICRO";
        return "NANO";
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
                            // F-18: the Campaign PK, verbatim — this is exactly what
                            // GetCampaignPerformanceExecutor resolves via
                            // CampaignRepository#findByIdAndWorkspaceId. Without it
                            // assembler.py cannot render the "[id=...]" marker the model is
                            // required to copy from, and get_campaign_performance is uncallable.
                            campaign.getId(),
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
