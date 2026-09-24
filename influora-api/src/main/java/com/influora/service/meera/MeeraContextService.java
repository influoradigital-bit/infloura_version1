package com.influora.service.meera;

import com.influora.common.ApiException;
import com.influora.common.JsonLists;
import com.influora.common.Rendered;
import com.influora.domain.entity.BrandAiCredit;
import com.influora.domain.entity.BrandProfile;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.CampaignTemplate;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.CreatorAgentPreferences;
import com.influora.domain.entity.CreatorMetric;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.DeliverableMetric;
import com.influora.domain.entity.MetaOAuthToken;
import com.influora.domain.entity.UtmCampaign;
import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.CampaignStatus;
import com.influora.domain.enums.CampaignTemplateScope;
import com.influora.domain.enums.CollaborationStatus;
import com.influora.domain.enums.CreatorDealStatuses;
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
import com.influora.repository.MetaOAuthTokenRepository;
import com.influora.repository.UtmCampaignRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.service.analytics.AnalyticsService;
import com.influora.service.scoring.CreatorTiers;
import com.influora.web.dto.analytics.AnalyticsDtos.CreatorAccountInsightsResponse;
import com.influora.web.dto.analytics.AnalyticsDtos.CreatorDemographicsResponse;
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
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(MeeraContextService.class);

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

    // T-MEERA-CREATOR-PHASE-B (SPEC.md 3.6, B0-11): ACTIVE_DEAL_STATUSES was `private static final`
    // here, and the Phase-B creator tool executors sit in com.influora.service.meera.tool.creator —
    // a different package, so even package-private would not have reached it. Moved verbatim to
    // CreatorDealStatuses.ACTIVE rather than copied, so `deals_summary.active_count` below and
    // `get_my_deals` in the tool surface cannot drift apart in front of the creator.

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
    private final AnalyticsService analyticsService;
    private final MetaOAuthTokenRepository metaOAuthTokenRepository;

    /** T-CREATOR-CREDITS-V2 (SPEC.md B20) — the USD backstop override, emitted only when the flag is on. */
    private final com.influora.config.CreatorCreditProperties creatorCreditProperties;

    /**
     * Creator Meera audience knowledge (Swapnil 2026-09-21) - the explicit value {@code
     * audience_summary} carries when this creator has no audience snapshot (Instagram not
     * connected, or the weekly demographics job has not produced one yet). Never zeros, never a
     * category-based guess: the creator persona is told to say this plainly and suggest connecting.
     */
    public static final String AUDIENCE_NOT_AVAILABLE =
            "not available (Instagram not connected, or no audience snapshot yet)";

    /**
     * What {@code account_insights_summary} carries when there are no account numbers for this
     * creator (Instagram not connected, or AccountInsightsJob has not fetched any yet). Never zeros.
     */
    public static final String ACCOUNT_INSIGHTS_NOT_AVAILABLE =
            "not available (Instagram not connected, or no account numbers fetched yet)";

    private static final int AUDIENCE_TOP_AGE_BANDS = 2;
    private static final int AUDIENCE_TOP_CITIES = 3;

    /** LOW-3 fix (post-554c347 review): control characters in a Meta city label must never be able
     * to inject a line break (or other control byte) into the creator's prompt block. */
    private static final Pattern CONTROL_CHARS = Pattern.compile("\\p{Cntrl}+");

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
            CreatorMetricsRepository creatorMetricsRepository,
            AnalyticsService analyticsService,
            MetaOAuthTokenRepository metaOAuthTokenRepository,
            com.influora.config.CreatorCreditProperties creatorCreditProperties) {
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
        this.analyticsService = analyticsService;
        this.metaOAuthTokenRepository = metaOAuthTokenRepository;
        this.creatorCreditProperties = creatorCreditProperties;
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

        // EV-008: only a Meta-synced row may be quoted unlabelled. The newest row of ANY source
        // used to be read here, so a creator-declared platform (PortfolioService#declarePlatform,
        // CREATOR_REPORTED) became Meera's plain "N followers" - a number the creator persona is
        // told to quote verbatim, including into drafts a brand reads.
        Optional<CreatorMetric> latestMetric =
                creatorMetricsRepository
                        .findByCreatorProfileIdAndDataSourceOrderByTimeDesc(
                                profile.getId(), CreatorMetric.DATA_SOURCE_META_API, PageRequest.of(0, 1))
                        .stream()
                        .filter(CreatorMetric::isPlatformVerified)
                        .findFirst();
        Map<String, String> metricsSummary = buildMetricsSummary(profile, latestMetric, locale);
        // Keyed off THIS creator's own resolved profile id only - the same id every other read in
        // this method uses, never a caller-supplied creator id. The BRAND path never calls this.
        String audienceSummary = buildAudienceSummary(profile.getId(), locale);
        String accountInsightsSummary = buildAccountInsightsSummary(profile.getId(), locale);

        List<Collaboration> collaborations = collaborationRepository.findByCreatorId(creatorUserId);
        Map<String, Object> dealsSummary = buildDealsSummary(collaborations, locale);

        Map<String, Boolean> identity = new LinkedHashMap<>();
        identity.put("kyc_done", profile.getIdentityKycStatus() == VerificationStatus.VERIFIED);
        identity.put("gstin_present", profile.getGstin() != null && !profile.getGstin().isBlank());

        String tier = profile.getTierOverride() != null ? profile.getTierOverride().name() : CreatorTiers.derive(profile.getTotalFollowers());

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

        // T-MEERA-CREATOR-PHASE-B (B0-20) — hoisted out of the constructor call below because
        // tools_enabled is DERIVED from these three, and an inline ternary cannot be reused. The
        // three values that decide the tool offer and the three the wire carries are now provably
        // the same values, not two independent readings of the same row.
        int approvalLevel =
                prefs != null
                        ? prefs.getApprovalLevel()
                        : CreatorAgentPreferences.APPROVAL_LEVEL_DRAFT_ONLY;
        boolean represented = prefs != null && prefs.isRepresented();
        boolean negotiationHoldout = prefs != null && prefs.isNegotiationHoldout();

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
                audienceSummary,
                accountInsightsSummary,
                dealsSummary,
                approvalLevel,
                represented,
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
                resolveAiMonthlyCapUsd(prefs, locale),
                negotiationHoldout,
                // Rendered by Java, never by Python (SPEC.md §3.6). Rendered.date returns null for
                // a null date, and the record is @JsonInclude(NON_NULL), so a creator who is not
                // held out simply has no holdout_until key on the wire.
                prefs != null ? Rendered.date(prefs.getHoldoutUntil(), locale) : null,
                prefs != null && prefs.isRateCardShareable(),
                prefs != null ? prefs.getApprovedDraftCount() : 0,
                // T-MEERA-CREATOR-PHASE-B (B0-20, SPEC.md §3.3/§7.2) — the Wave-1 empty-list TODO
                // is discharged here. This is the ONLY production caller of toolNamesForLevel, and
                // therefore the only thing that makes the whole Wave-2 creator tool surface
                // reachable: an empty tools_enabled degrades to `tools = []` on the Python side, so
                // leaving it hardcoded meant the controller, both executors, the validator and the
                // creator scope mint could never be entered in production while every test on both
                // sides still passed. Asserted end-to-end (not on this method in isolation) by
                // MeeraContextServiceTest#testCreatorContextCarriesWiredToolNames — reverting this
                // argument to List.of() must turn that test red.
                CreatorToolScopes.toolNamesForLevel(approvalLevel, represented, negotiationHoldout),
                // V76 — creator-typed phone model; null (omitted on the wire) when not saved.
                prefs != null ? prefs.getPhoneModel() : null);
    }

    /**
     * Creator Meera audience knowledge (Swapnil 2026-09-21) - a compact, text-only summary of this
     * creator's own audience, reusing {@link AnalyticsService#getCreatorDemographicsForProfile}
     * (the read behind the creator's own GET /creator/analytics/demographics). Shape: {@code "Age:
     * 18-24 41%, 25-34 33%. Gender: women 58%, men 40%. Top cities: Mumbai / Pune / Delhi. As of 12
     * Sep 2026."} Percentages are of the age/gender total; cities are names only (Meta's own city
     * labels can contain a comma, hence the slash separator). No raw breakdown map leaves this
     * method, and Meta's aggregate breakdowns carry no follower identities to leak.
     *
     * <p>Returns {@link #AUDIENCE_NOT_AVAILABLE} when there is no snapshot, or when the snapshot
     * has neither a usable age/gender breakdown nor any city - never zeros and never a guess.
     *
     * <p>LOW-2 fix (post-554c347 review): a stored snapshot is never surfaced once the creator has
     * no live Meta connection — our published Meta data policy promises data is not used once no
     * longer needed, and revoking the connection means it is no longer needed for Meera. "Live" is
     * checked the same way {@code MetricsPollingJob#onCreatorConnected} decides it for this exact
     * creator-owned key-space (workspace_id IS NULL): a non-revoked {@link MetaOAuthToken} row that
     * is either not expiring or not yet expired. This class never re-derives that definition.
     *
     * <p>LOW-1 fix (post-554c347 review): the demographics read itself is guarded so a failure
     * there (e.g. a JSON decode error on a stored breakdown, per this class's own comment on {@code
     * ageGender} below) degrades to the not-available summary instead of failing the whole CREATOR
     * context — logged with the creator profile id only, never any breakdown data.
     */
    /**
     * The creator's own account numbers for the last 28 full days, as one line Meera can quote:
     * "Last 28 days (26 Aug 2026 to 22 Sep 2026): 12,400 accounts reached, 48,210 views, ...".
     * Same rules as {@link #buildAudienceSummary}: only with a live Meta connection, a failed read
     * degrades to {@link #ACCOUNT_INSIGHTS_NOT_AVAILABLE}, and a number Meta did not return is left
     * out rather than shown as 0.
     */
    private String buildAccountInsightsSummary(String creatorProfileId, Locale locale) {
        if (!hasLiveMetaConnection(creatorProfileId)) {
            return ACCOUNT_INSIGHTS_NOT_AVAILABLE;
        }
        CreatorAccountInsightsResponse insights;
        try {
            insights = analyticsService.getCreatorAccountInsightsForProfile(creatorProfileId);
        } catch (RuntimeException e) {
            log.warn(
                    "MeeraContextService: account insights read failed for creator profile {};"
                            + " falling back to the not-available summary",
                    creatorProfileId,
                    e);
            return ACCOUNT_INSIGHTS_NOT_AVAILABLE;
        }
        if (insights == null || !insights.hasData()) {
            return ACCOUNT_INSIGHTS_NOT_AVAILABLE;
        }
        List<String> parts = new ArrayList<>();
        addCount(parts, insights.reach(), "accounts reached", locale);
        addCount(parts, insights.views(), "views", locale);
        addCount(parts, insights.totalInteractions(), "interactions", locale);
        addCount(parts, insights.accountsEngaged(), "accounts engaged", locale);
        addCount(parts, insights.profileLinksTaps(), "profile-link taps", locale);
        if (parts.isEmpty()) {
            return ACCOUNT_INSIGHTS_NOT_AVAILABLE;
        }
        return "Last 28 days ("
                + Rendered.date(insights.periodStart(), locale)
                + " to "
                + Rendered.date(insights.periodEnd(), locale)
                + "): "
                + String.join(", ", parts)
                + ".";
    }

    private static void addCount(List<String> parts, Long value, String label, Locale locale) {
        if (value != null) {
            parts.add(Rendered.money(BigDecimal.valueOf(value), locale) + " " + label);
        }
    }

    private String buildAudienceSummary(String creatorProfileId, Locale locale) {
        if (!hasLiveMetaConnection(creatorProfileId)) {
            return AUDIENCE_NOT_AVAILABLE;
        }

        CreatorDemographicsResponse demographics;
        try {
            demographics = analyticsService.getCreatorDemographicsForProfile(creatorProfileId);
        } catch (RuntimeException e) {
            log.warn(
                    "MeeraContextService: audience demographics read failed for creator profile {};"
                            + " falling back to the not-available summary",
                    creatorProfileId,
                    e);
            return AUDIENCE_NOT_AVAILABLE;
        }
        if (demographics == null || !demographics.hasData()) {
            return AUDIENCE_NOT_AVAILABLE;
        }

        // Map<String, ?> on purpose: the breakdowns are decoded from JSON with a raw Map.class, so
        // at runtime a value is usually an Integer despite the declared Long. Reading it as Object
        // avoids the implicit (Long) cast that would throw ClassCastException.
        Map<String, Long> ageTotals = new LinkedHashMap<>();
        Map<String, Long> genderTotals = new LinkedHashMap<>();
        long ageGenderTotal = 0;
        Map<String, ?> ageGender = demographics.ageGenderBreakdown();
        if (ageGender != null) {
            for (Map.Entry<String, ?> entry : ageGender.entrySet()) {
                long count = countOf(entry.getValue());
                String[] genderAndAge = splitAgeGenderKey(entry.getKey());
                if (count <= 0 || genderAndAge == null) {
                    continue;
                }
                genderTotals.merge(genderAndAge[0], count, Long::sum);
                ageTotals.merge(genderAndAge[1], count, Long::sum);
                ageGenderTotal += count;
            }
        }

        List<String> parts = new ArrayList<>();
        NumberFormat pct = NumberFormat.getIntegerInstance(locale);
        if (ageGenderTotal > 0) {
            final long total = ageGenderTotal;
            parts.add(
                    "Age: "
                            + String.join(
                                    ", ",
                                    topEntries(ageTotals, AUDIENCE_TOP_AGE_BANDS).stream()
                                            .map(e -> e.getKey() + " " + pct.format(Math.round(e.getValue() * 100.0 / total)) + "%")
                                            .toList()));
            parts.add(
                    "Gender: "
                            + String.join(
                                    ", ",
                                    topEntries(genderTotals, genderTotals.size()).stream()
                                            .map(e -> genderLabel(e.getKey()) + " " + pct.format(Math.round(e.getValue() * 100.0 / total)) + "%")
                                            .toList()));
        }

        Map<String, Long> cityCounts = new LinkedHashMap<>();
        Map<String, ?> cities = demographics.cityBreakdown();
        if (cities != null) {
            for (Map.Entry<String, ?> entry : cities.entrySet()) {
                long count = countOf(entry.getValue());
                if (count > 0 && entry.getKey() != null && !entry.getKey().isBlank()) {
                    // LOW-3 fix (post-554c347 review): a raw Meta city label can carry \r/\n/\t (or
                    // other control bytes) that would otherwise start a new line inside the
                    // creator's single-line prompt block. Sanitize before it ever joins parts/asOf
                    // below. merge (not put): two distinct raw labels can collapse onto the same
                    // sanitized one, and their counts must combine rather than one silently winning.
                    String sanitizedCity = sanitizeLabel(entry.getKey());
                    if (!sanitizedCity.isBlank()) {
                        cityCounts.merge(sanitizedCity, count, Long::sum);
                    }
                }
            }
        }
        if (!cityCounts.isEmpty()) {
            parts.add(
                    "Top cities: "
                            + String.join(
                                    " / ",
                                    topEntries(cityCounts, AUDIENCE_TOP_CITIES).stream().map(Map.Entry::getKey).toList()));
        }

        if (parts.isEmpty()) {
            return AUDIENCE_NOT_AVAILABLE;
        }
        String asOf = Rendered.date(demographics.fetchedAt(), locale);
        if (asOf != null) {
            parts.add("As of " + asOf);
        }
        return String.join(". ", parts) + ".";
    }

    private static long countOf(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    /** LOW-3 fix (post-554c347 review): strips/replaces control characters (\r, \n, \t, and any
     * other {@code \p{Cntrl}} byte) with a space, then trims. Used on every raw Meta city label
     * before it can reach the single-line audience summary. */
    private static String sanitizeLabel(String raw) {
        return CONTROL_CHARS.matcher(raw).replaceAll(" ").strip();
    }

    /**
     * LOW-2 fix (post-554c347 review): reuses the exact "live creator-owned Meta connection" check
     * {@code MetricsPollingJob#onCreatorConnected} already uses for this same creator-owned
     * key-space (workspace_id IS NULL) — a non-revoked {@link MetaOAuthToken} row whose {@code
     * expiresAt} is either absent or still in the future. Read-only; this class does not decide or
     * change connection state, only asks the same repository the rest of the codebase already asks.
     */
    private boolean hasLiveMetaConnection(String creatorProfileId) {
        return metaOAuthTokenRepository
                .findByCreatorProfileIdAndWorkspaceIdIsNullAndRevokedFalse(creatorProfileId)
                .filter(t -> t.getExpiresAt() == null || t.getExpiresAt().isAfter(Instant.now()))
                .isPresent();
    }

    /** Largest first; ties broken by key so the same snapshot always renders the same text. */
    private static List<Map.Entry<String, Long>> topEntries(Map<String, Long> counts, int limit) {
        return counts.entrySet().stream()
                .sorted(
                        Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder())
                                .thenComparing(Map.Entry.comparingByKey()))
                .limit(limit)
                .toList();
    }

    /**
     * {gender code, age band} for one age/gender key, or null. AudienceDemographicsJob stores
     * {@code "18-24_female"} (from {@code follower_demographics}, 2026-09-24), the form every screen
     * reads; Meta's removed {@code audience_gender_age} metric used {@code "F.18-24"}, still accepted
     * here so an older row reads the same.
     */
    static String[] splitAgeGenderKey(String key) {
        if (key == null) {
            return null;
        }
        int underscore = key.lastIndexOf('_');
        if (underscore > 0 && underscore < key.length() - 1) {
            String gender =
                    switch (key.substring(underscore + 1).toLowerCase(Locale.ROOT)) {
                        case "female" -> "F";
                        case "male" -> "M";
                        default -> "U";
                    };
            return new String[] {gender, key.substring(0, underscore)};
        }
        int dot = key.indexOf('.');
        if (dot > 0 && dot < key.length() - 1) {
            return new String[] {key.substring(0, dot), key.substring(dot + 1)};
        }
        return null;
    }

    private static String genderLabel(String metaCode) {
        return switch (metaCode) {
            case "F" -> "women";
            case "M" -> "men";
            default -> "unspecified";
        };
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

    /**
     * T-CREATOR-CREDITS-V2 (SPEC.md B20, C22) — with the flag on, {@code ai_monthly_cap_usd} is
     * ALWAYS present and is at least {@code creator-credits.usd-backstop-monthly} (default 25.00):
     * 30 credits/day * 31 days costs at most ~$0.74/day in real spend, which the pre-existing
     * $0.75/mo default ({@code AI_CREATOR_MONTHLY_CAP_USD}) would otherwise cut off within the
     * FIRST paid day. With the flag off this is byte-identical to the pre-B20 behaviour (locale-
     * formatted, {@code null}/omitted when the creator has no override).
     *
     * <p>Rendered in {@link Locale#US} (a plain {@code "25.00"}, never grouped), NOT the creator's
     * own locale — influora-ai's {@code spend_tracker.creator_monthly_cap_usd} does {@code
     * Decimal(str(override))}, which must never see a grouping separator or a comma decimal point.
     */
    private String resolveAiMonthlyCapUsd(CreatorAgentPreferences prefs, Locale locale) {
        if (!creatorCreditProperties.isEnabled()) {
            return prefs != null ? formatCapUsd(prefs.getAiMonthlyCapUsd(), locale) : null;
        }
        BigDecimal override = prefs != null ? prefs.getAiMonthlyCapUsd() : null;
        BigDecimal backstop = creatorCreditProperties.getUsdBackstopMonthly();
        BigDecimal effective = (override != null && override.compareTo(backstop) > 0) ? override : backstop;
        return effective.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
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
        // EV-008: the profile totals are VERIFIED (Meta-synced platforms) or IMPORTED
        // (Marketplace/admin import) since F-0965; anything else is a legacy self-reported figure.
        String fallbackLabel = profileTotalsLabel(profile.getFollowersSource());
        if (latestMetric.isPresent()) {
            long followers = latestMetric.get().getFollowers();
            summary.put("followers", formatDecimal(BigDecimal.valueOf(followers), locale) + " followers");
        } else if (profile.getTotalFollowers() > 0) {
            summary.put(
                    "followers",
                    formatDecimal(BigDecimal.valueOf(profile.getTotalFollowers()), locale)
                            + " followers"
                            + fallbackLabel);
        }
        // else: no verified metric and no self-reported total — omit the key so the "Instagram
        // not connected yet" honest-state branch fires downstream instead of a fabricated zero.

        Long reach = latestMetric.map(CreatorMetric::getAvgReachPerPost).orElse(null);
        if (reach != null) {
            summary.put("reach_30d", formatDecimal(BigDecimal.valueOf(reach), locale) + " avg reach per post");
        }

        BigDecimal engagement = latestMetric.map(CreatorMetric::getAvgEngagementRate).orElse(null);
        String engagementLabel = "";
        if (latestMetric.isEmpty() && profile.getEngagementRate() != null) {
            engagement = profile.getEngagementRate();
            engagementLabel = fallbackLabel;
        }
        if (engagement != null) {
            NumberFormat pctFormat = NumberFormat.getNumberInstance(locale);
            pctFormat.setMaximumFractionDigits(1);
            summary.put("engagement_rate", pctFormat.format(engagement) + "% engagement" + engagementLabel);
        }
        return summary;
    }

    /** EV-008 - the provenance suffix for a figure read from the CreatorProfile totals. */
    private static String profileTotalsLabel(String followersSource) {
        if (com.influora.service.FollowerTotals.VERIFIED.equals(followersSource)) {
            return "";
        }
        if (com.influora.service.FollowerTotals.IMPORTED.equals(followersSource)) {
            return " (imported, not verified)";
        }
        return " (self-reported, not verified)";
    }

    private static Map<String, Object> buildDealsSummary(List<Collaboration> collaborations, Locale locale) {
        long activeCount =
                collaborations.stream().filter(c -> CreatorDealStatuses.ACTIVE.contains(c.getStatus())).count();
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

    // T-MEERA-CREATOR-PHASE-B (SPEC.md 3.6, B0-11): the private `deriveTier` copy that used to sit
    // here — and whose own javadoc already flagged it as a duplicate of
    // CreatorAgentBaselineService.deriveTier — is now CreatorTiers.derive. The three copies were
    // byte-identical, MEGA branch included, so this changed no output.

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
