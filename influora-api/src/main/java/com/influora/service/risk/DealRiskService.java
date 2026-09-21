package com.influora.service.risk;

import com.influora.common.ApiException;
import com.influora.common.JsonLists;
import com.influora.common.Rendered;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.CreatorBrief;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.DealMessage;
import com.influora.domain.entity.Deliverable;
import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.DealMessageKind;
import com.influora.domain.enums.DealSenderType;
import com.influora.domain.enums.DeliverableType;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.CreatorBriefRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.DealMessageRepository;
import com.influora.repository.DeliverableRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.service.AuditLogService;
import com.influora.service.CreatorAgentPreferencesService;
import com.influora.service.rates.QuoteDeliverableType;
import com.influora.service.rates.RateQuoteService;
import com.influora.service.risk.RiskContext.ActiveDeal;
import com.influora.service.risk.rules.BarterRule;
import com.influora.service.risk.rules.BelowFloorRule;
import com.influora.service.risk.rules.BlockedBrandRule;
import com.influora.service.risk.rules.CalendarOverloadRule;
import com.influora.service.risk.rules.CompetitorConflictRule;
import com.influora.service.risk.rules.ExcludedCategoryRule;
import com.influora.service.risk.rules.ExclusivityLongRule;
import com.influora.service.risk.rules.HideDisclosureRule;
import com.influora.service.risk.rules.OffPlatformPaymentRule;
import com.influora.service.risk.rules.PartnershipAdsRequestRule;
import com.influora.service.risk.rules.RegulatedCategoryRule;
import com.influora.service.risk.rules.RiskRule;
import com.influora.service.risk.rules.UsageLongRule;
import com.influora.service.risk.rules.UsagePerpetualRule;
import com.influora.service.risk.rules.VagueDeliverablesRule;
import com.influora.web.dto.brief.BriefDtos.BriefExtraction;
import com.influora.web.dto.brief.BriefDtos.DeliverableLine;
import com.influora.web.dto.creator.CreatorAgentDtos.PreferencesResponse;
import com.influora.web.dto.deal.DealDtos.DeliverableSlot;
import com.influora.web.dto.meera.CreatorToolDtos.PackageQuote;
import com.influora.web.dto.meera.CreatorToolDtos.RiskFlag;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * T-MEERA-CREATOR-PHASE-B (SPEC.md &sect;5.1/&sect;5.2, B4) — the deal risk engine: fourteen pure
 * rules, one input shape, and the two loaders that build that shape from a pasted brief or a live
 * deal.
 *
 * <p><b>Info barrier.</b> Preferences (and therefore the creator's floors) are read through
 * {@link CreatorAgentPreferencesService#getByProfileId}, never through
 * {@code CreatorAgentPreferencesRepository} — {@code InfoBarrierTest} scans {@code service/**} for
 * exactly that import, and the floors these flags quote are the numbers the barrier exists to keep
 * away from a brand.
 *
 * <p><b>Rendering.</b> Every number in a flag is already a string, formatted here in the creator's
 * locale via {@link Rendered} (SPEC.md &sect;0.4). Python never formats.
 *
 * <p><b>The quote seam (Wave 3 round 2).</b> All three entry points now price the package through
 * {@link RateQuoteService#quoteForRisk} and hand the result to {@link RiskContext#quote}, so
 * {@code BELOW_FLOOR} and {@code BARTER} compare against one floor computed by the pricing engine
 * rather than a second opinion computed in {@link Floors}. {@link #quoteFor} explains what happens
 * when a quote cannot be produced, which is a normal case and not an error.
 */
@Service
public class DealRiskService {

    private static final Logger log = LoggerFactory.getLogger(DealRiskService.class);

    /** SPEC.md &sect;5.2 — the {@code eventType} of the shadow-mode off-platform audit row. */
    public static final String OFF_PLATFORM_AUDIT_EVENT = "OFF_PLATFORM_HINT";

    /** Makes it explicit in the audit trail that this row observed something and blocked nothing. */
    public static final String OFF_PLATFORM_AUDIT_REASON = "SHADOW_MODE";

    public static final String TARGET_DEAL = "DEAL";
    public static final String TARGET_BRIEF = "BRIEF";

    /**
     * The fourteen rules of SPEC.md &sect;5.2.
     *
     * <p>&sect;1 and &sect;11 of the spec say "ten"; &sect;5.2 lists fourteen and fourteen is what
     * is built. Order here is evaluation order only — the returned list is sorted by severity, so
     * this list is free to read in the order the table does.
     */
    private static final List<RiskRule> RULES =
            List.of(
                    new BelowFloorRule(),
                    new UsagePerpetualRule(),
                    new UsageLongRule(),
                    new ExcludedCategoryRule(),
                    new BlockedBrandRule(),
                    new OffPlatformPaymentRule(),
                    new CompetitorConflictRule(),
                    new ExclusivityLongRule(),
                    new VagueDeliverablesRule(),
                    new HideDisclosureRule(),
                    new BarterRule(),
                    new RegulatedCategoryRule(),
                    new CalendarOverloadRule(),
                    new PartnershipAdsRequestRule());

    private final CreatorProfileRepository creatorProfileRepository;
    private final CreatorAgentPreferencesService preferencesService;
    private final CollaborationRepository collaborationRepository;
    private final CampaignRepository campaignRepository;
    private final WorkspaceRepository workspaceRepository;
    private final DealMessageRepository dealMessageRepository;
    private final DeliverableRepository deliverableRepository;
    private final CreatorBriefRepository creatorBriefRepository;
    private final AuditLogService auditLogService;
    private final RateQuoteService rateQuoteService;

    public DealRiskService(
            CreatorProfileRepository creatorProfileRepository,
            CreatorAgentPreferencesService preferencesService,
            CollaborationRepository collaborationRepository,
            CampaignRepository campaignRepository,
            WorkspaceRepository workspaceRepository,
            DealMessageRepository dealMessageRepository,
            DeliverableRepository deliverableRepository,
            CreatorBriefRepository creatorBriefRepository,
            AuditLogService auditLogService,
            RateQuoteService rateQuoteService) {
        this.creatorProfileRepository = creatorProfileRepository;
        this.preferencesService = preferencesService;
        this.collaborationRepository = collaborationRepository;
        this.campaignRepository = campaignRepository;
        this.workspaceRepository = workspaceRepository;
        this.dealMessageRepository = dealMessageRepository;
        this.deliverableRepository = deliverableRepository;
        this.creatorBriefRepository = creatorBriefRepository;
        this.auditLogService = auditLogService;
        this.rateQuoteService = rateQuoteService;
    }

    // ------------------------------------------------------------------
    // SPEC.md 5.1 — the three entry points
    // ------------------------------------------------------------------

    /**
     * Risk flags for a live deal. Builds an extraction-shaped VIEW of the collaboration (and its
     * campaign, messages and deliverables) so the rules see the same shape a pasted brief gives
     * them, then delegates.
     *
     * @param creatorProfileId a {@code creator_profiles.id} — NOT a {@code users.id}; note that
     *     {@code Collaboration.creatorId} is a user id, which is why the lookup below goes through
     *     {@link CreatorProfile#getUserId()}
     */
    @Transactional(readOnly = true)
    public List<RiskFlag> evaluateDeal(String creatorProfileId, String collaborationId) {
        CreatorProfile profile = requireProfile(creatorProfileId);
        PreferencesResponse prefs = preferencesService.getByProfileId(creatorProfileId);
        Collaboration collaboration =
                collaborationRepository
                        .findByIdAndCreatorId(collaborationId, profile.getUserId())
                        .orElseThrow(
                                () -> new ApiException("DEAL_NOT_FOUND", "Deal not found", HttpStatus.NOT_FOUND));

        Campaign campaign = campaignRepository.findById(collaboration.getCampaignId()).orElse(null);
        Workspace workspace = workspaceOf(campaign);
        List<DealMessage> messages =
                dealMessageRepository.findByCollaborationIdOrderByCreatedAtAsc(collaboration.getId());
        List<Deliverable> deliverables =
                deliverableRepository.findByCollaborationIdOrderBySlotIndexAsc(collaboration.getId());

        // Resolved ONCE and handed to both the view and the quote, so the risk view and the quoted
        // floor can never disagree about what is on the table. See packageOnTheTable.
        List<DeliverableSlot> onTheTable = packageOnTheTable(deliverables, messages);
        BriefExtraction extraction = viewOf(collaboration, campaign, workspace, deliverables, onTheTable);
        RiskContext ctx =
                new RiskContext(
                        profile,
                        prefs,
                        extraction,
                        collaboration,
                        activeDealsFor(profile, collaboration.getId()),
                        quoteFor(profile, prefs, extraction, onTheTable),
                        extraction.brandName(),
                        campaign == null ? null : campaign.getWorkspaceId(),
                        dealText(collaboration, messages),
                        lastBrandMessage(messages),
                        localeFor(prefs),
                        Instant.now(),
                        TARGET_DEAL);
        return evaluate(ctx, TARGET_DEAL);
    }

    /**
     * Risk flags for a pasted brief. The brief's stored extraction is the input; when it has none
     * yet (extraction pending, or the AI call failed and no fallback ran) the rules still run
     * against the raw text, so the regex halves of {@code OFF_PLATFORM_PAYMENT},
     * {@code HIDE_DISCLOSURE}, {@code USAGE_PERPETUAL} and {@code VAGUE_DELIVERABLES} are not lost.
     */
    @Transactional(readOnly = true)
    public List<RiskFlag> evaluateBrief(String creatorProfileId, String briefId) {
        CreatorProfile profile = requireProfile(creatorProfileId);
        PreferencesResponse prefs = preferencesService.getByProfileId(creatorProfileId);
        CreatorBrief brief =
                creatorBriefRepository
                        .findByIdAndCreatorProfileId(briefId, creatorProfileId)
                        .orElseThrow(
                                () -> new ApiException("BRIEF_NOT_FOUND", "Brief not found", HttpStatus.NOT_FOUND));

        BriefExtraction extraction =
                Optional.ofNullable(JsonLists.objectFromJson(brief.getExtractedJson(), BriefExtraction.class))
                        .orElseGet(DealRiskService::emptyExtraction);
        Collaboration collaboration =
                brief.getCollaborationId() == null
                        ? null
                        : collaborationRepository
                                .findByIdAndCreatorId(brief.getCollaborationId(), profile.getUserId())
                                .orElse(null);
        Campaign campaign =
                collaboration == null
                        ? null
                        : campaignRepository.findById(collaboration.getCampaignId()).orElse(null);

        RiskContext ctx =
                new RiskContext(
                        profile,
                        prefs,
                        extraction,
                        collaboration,
                        activeDealsFor(profile, collaboration == null ? null : collaboration.getId()),
                        quoteFor(profile, prefs, extraction, List.of()),
                        brandNameOf(extraction, brief, campaign, workspaceOf(campaign)),
                        campaign == null ? null : campaign.getWorkspaceId(),
                        brief.getRawText(),
                        // Deliberately null: PARTNERSHIP_ADS_REQUEST is "only in evaluateDeal".
                        null,
                        localeFor(prefs),
                        Instant.now(),
                        TARGET_BRIEF);
        return evaluate(ctx, TARGET_BRIEF);
    }

    /**
     * SPEC.md &sect;5.1's third entry point — an extraction the caller already holds (the
     * paste-and-read flow evaluates before it has persisted anything).
     *
     * <p>Prices the extraction itself, like {@link #evaluateBrief}. A caller that already holds a
     * quote it wants used verbatim should build a {@link RiskContext} and call
     * {@link #evaluate(RiskContext, String)}, which is the real entry point this and the two
     * loaders above all funnel into.
     *
     * <p><b>K-2 HIGH fix (Kabir, KABIR-CONSENT-0917.md Q5; Priya RULINGS-U-0917.md round 3
     * &sect;1).</b> {@code text} used to be hardcoded {@code null} here, which meant the regex
     * halves of {@code OFF_PLATFORM_PAYMENT}, {@code HIDE_DISCLOSURE}, {@code USAGE_PERPETUAL} and
     * {@code VAGUE_DELIVERABLES} — {@code RiskText.matches} is false for a null/blank text — could
     * only ever fire off the extractor's OWN boolean hint. A brand whose text said "pay by UPI
     * after posting, skip the #ad" but whose extraction hint came back false (a targeted prompt, or
     * simply a miss) produced a creator-facing card with neither non-dismissible flag, on the one
     * path every successful AI extraction takes. {@link #evaluateBrief} already passes {@code
     * brief.getRawText()} for exactly this reason (see its own javadoc); this method now does too,
     * fed by its one caller, {@code CreatorBriefService.analyse}, which already holds the same
     * {@code CreatorBrief} entity {@code evaluateBrief} reads it from later.
     *
     * <p><b>{@code lastBrandMessage} stays {@code null} — do not feed it a real value.</b> {@link
     * RiskContext}'s own javadoc says it is non-null ONLY on the {@code evaluateDeal} path, which
     * is how {@code PARTNERSHIP_ADS_REQUEST} enforces "only in evaluateDeal" without a separate
     * flag. Threading a real value through here would let that rule fire on a pasted or
     * platform-read brief, which K-2 does not ask for and would need its own review.
     *
     * <p><b>No re-evaluation of already-frozen rows.</b> {@code risk_flags_json} is a snapshot,
     * written once by {@link CreatorBriefWriter#saveAnalysis} and never recomputed on read (see the
     * class javadoc on {@link CreatorBriefService}). This fix only changes what a FUTURE analysis
     * computes; a brief analysed before this fix landed keeps whatever the null-text bug produced.
     * Priya's ruling: no backfill path, no quiet recompute-on-read — only a designed migration with
     * its own review, should stored flags ever need correcting. The residual is bounded because no
     * real (non-test) {@code creator_briefs} row can exist yet: the table's migration
     * (V20260910100100) has not reached a remote branch this clone can see, and Phase A is not
     * deployed. The first deploy that carries this fix must delete any {@code creator_briefs} row
     * created before it, rather than trust its flags (owner: meera, at that deploy).
     */
    @Transactional(readOnly = true)
    public List<RiskFlag> evaluateExtraction(
            CreatorProfile profile,
            PreferencesResponse prefs,
            BriefExtraction extraction,
            Collaboration collaborationOrNull,
            String text) {
        Campaign campaign =
                collaborationOrNull == null
                        ? null
                        : campaignRepository.findById(collaborationOrNull.getCampaignId()).orElse(null);
        BriefExtraction safeExtraction = extraction == null ? emptyExtraction() : extraction;
        RiskContext ctx =
                new RiskContext(
                        profile,
                        prefs,
                        safeExtraction,
                        collaborationOrNull,
                        activeDealsFor(profile, collaborationOrNull == null ? null : collaborationOrNull.getId()),
                        quoteFor(profile, prefs, safeExtraction, List.of()),
                        brandNameOf(safeExtraction, null, campaign, workspaceOf(campaign)),
                        campaign == null ? null : campaign.getWorkspaceId(),
                        text,
                        null,
                        localeFor(prefs),
                        Instant.now(),
                        collaborationOrNull == null ? TARGET_BRIEF : TARGET_DEAL);
        return evaluate(ctx, collaborationOrNull == null ? TARGET_BRIEF : TARGET_DEAL);
    }

    // ------------------------------------------------------------------
    // The quote seam (Wave 3 round 2)
    // ------------------------------------------------------------------

    /**
     * The priced package the rules compare against, or {@code null} when one genuinely cannot be
     * produced.
     *
     * <p><b>Why this exists at all.</b> {@link RiskContext#quote} used to be null on every path,
     * because {@code RateQuoteService} landed in a different wave; {@code BELOW_FLOOR} and
     * {@code BARTER} therefore always fell back to {@link Floors}, which folds a creator's three
     * stored floors onto whatever deliverable types the extraction happens to name. That fallback
     * is now the degraded branch, not the normal one.
     *
     * <p><b>It degrades, it does not throw.</b> Pricing reaches five repositories and the
     * benchmark estimator; a creator with no metric row, no history and no niche is an ordinary
     * case, and a malformed proposal card is a reachable one. None of that may cost the creator
     * her risk flags — {@code HIDE_DISCLOSURE} and {@code REGULATED_CATEGORY} do not need a quote
     * and must still fire. So every failure here is logged and swallowed, and {@link Floors} takes
     * over exactly as it did before this seam was closed.
     */
    private PackageQuote quoteFor(
            CreatorProfile profile,
            PreferencesResponse prefs,
            BriefExtraction extraction,
            List<DeliverableSlot> deliverables) {
        if (profile == null || prefs == null) {
            return null;
        }
        try {
            return rateQuoteService.quoteForRisk(profile, prefs, extraction, deliverables);
        } catch (RuntimeException e) {
            log.warn(
                    "Rate quote unavailable for risk evaluation of creator profile {}; falling back to"
                            + " stored floors: {}",
                    profile.getId(),
                    e.toString());
            return null;
        }
    }

    /**
     * The deliverables actually on the table for a live deal.
     *
     * <p>{@code Deliverable} rows are materialised when a contract is drafted
     * ({@code ContractService}), so a deal in {@code INVITED}, {@code APPLIED} or
     * {@code IN_NEGOTIATION} has none — and those are precisely the deals a creator asks Meera
     * about, because they are the ones she can still counter. Pre-contract the package lives on
     * the latest proposal card's metadata, which is where {@code RateQuoteService} already reads
     * it for its own history normalisation.
     *
     * <p><b>This is the one resolver, and {@link #viewOf} now reads it too.</b> It used to feed only
     * the quote, which left the extraction-shaped view naming no deliverables on every pre-contract
     * deal — so {@link Floors} priced a three-reel package as one reel and
     * {@code VAGUE_DELIVERABLES} could not tell "no contract yet" from "nobody named a count". Both
     * callers take their package from this one call in {@link #evaluateDeal}, so the floor the
     * creator is quoted and the package the rules reason about cannot come apart.
     *
     * <p><b>What it must NOT do is substitute anything.</b> An absent, empty or unreadable proposal
     * card returns an EMPTY list here, and it has to stay empty all the way into the view:
     * {@code RateQuoteService.normaliseSlots} prices an empty package as a single REEL (SPEC.md
     * &sect;4.3 1a), and if that substitution reached the view then a deal nobody has scoped would
     * report one reel instead of nothing and {@code VAGUE_DELIVERABLES} would go permanently dark.
     * The substitution lives inside {@code RateQuoteService.compute} and nowhere on this path;
     * {@code RateQuoteService.slotsFromProposalMetadata} returns an empty list for null,
     * unparseable and {@code deliverables}-free metadata, which is exactly what is wanted here.
     */
    private static List<DeliverableSlot> packageOnTheTable(
            List<Deliverable> deliverables, List<DealMessage> messages) {
        if (deliverables != null && !deliverables.isEmpty()) {
            return deliverables.stream()
                    .map(d -> new DeliverableSlot(d.getType() == null ? null : d.getType().name(), 1))
                    .filter(slot -> slot.type() != null)
                    .toList();
        }
        if (messages == null) {
            return List.of();
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            DealMessage message = messages.get(i);
            if (message != null && message.getKind() == DealMessageKind.proposal) {
                return RateQuoteService.slotsFromProposalMetadata(message.getMetadataJson());
            }
        }
        return List.of();
    }

    // ------------------------------------------------------------------
    // The engine
    // ------------------------------------------------------------------

    /**
     * Runs every rule, applies the deal-value escalation centrally, sorts most-severe first, and
     * writes the shadow-mode audit row when {@code OFF_PLATFORM_PAYMENT} came back.
     *
     * <p>Public so a caller that has assembled its own {@link RiskContext} — including a
     * {@link PackageQuote}, which the three loaders above cannot supply yet — can reach the rules
     * without going through a repository load.
     *
     * <p><b>{@code target} is stamped onto the context before the rules run</b>
     * ({@link RiskContext#withTarget}), rather than being a second parameter the rules cannot see, so
     * a rule and the audit row below can never read two different answers for the same evaluation.
     * {@code VAGUE_DELIVERABLES} used to branch on it — an empty deliverable list meant "the brief
     * was vague" on one target and "no contract exists yet" on the other — and no longer does:
     * {@link #viewOf} now names the package on the table, so an empty list means the same thing on
     * both targets. No rule branches on the target today.
     */
    public List<RiskFlag> evaluate(RiskContext ctx, String target) {
        RiskContext scoped = ctx.withTarget(target);
        DealValue.Band band = DealValue.bandOf(DealValue.of(scoped));
        List<RiskFlag> flags = new ArrayList<>();
        for (RiskRule rule : RULES) {
            rule.apply(scoped).map(flag -> scaled(flag, band)).ifPresent(flags::add);
        }
        flags.sort(
                Comparator.comparing((RiskFlag flag) -> severityRank(flag.severity()))
                        .reversed()
                        .thenComparing(RiskFlag::code));
        recordOffPlatformHintIfPresent(scoped, flags, target);
        return List.copyOf(flags);
    }

    /** SPEC.md &sect;5.2 — deal value escalates a flag; it never softens one. See {@link RiskSeverity}. */
    private static RiskFlag scaled(RiskFlag flag, DealValue.Band band) {
        RiskSeverity base = RiskSeverity.parse(flag.severity());
        if (base == null) {
            return flag;
        }
        RiskSeverity scaled = RiskSeverity.scaled(base, band);
        if (scaled == base) {
            return flag;
        }
        return new RiskFlag(
                flag.code(),
                scaled.name(),
                flag.title(),
                flag.detail(),
                flag.cost(),
                flag.action(),
                flag.data(),
                flag.dismissible());
    }

    private static int severityRank(String severity) {
        RiskSeverity parsed = RiskSeverity.parse(severity);
        return parsed == null ? -1 : parsed.ordinal();
    }

    /**
     * SPEC.md &sect;5.2 — the shadow-mode record for {@code OFF_PLATFORM_PAYMENT}.
     *
     * <p><b>The row carries the brand's workspace id and nothing else.</b> Not the creator's name,
     * not her id, not the message that matched, not the matched terms. The point of the row is a
     * per-brand pattern ("this brand keeps steering creators off-platform"), and every additional
     * field would turn a fraud signal into a log of what a named creator was privately offered.
     * The same discipline the rest of {@link AuditLogService} documents.
     *
     * <p>Written here rather than inside {@link OffPlatformPaymentRule} so the rule stays a pure
     * function — see {@link RiskRule}.
     */
    private void recordOffPlatformHintIfPresent(RiskContext ctx, List<RiskFlag> flags, String target) {
        boolean hinted = flags.stream().anyMatch(flag -> OffPlatformPaymentRule.CODE.equals(flag.code()));
        if (!hinted) {
            return;
        }
        log.info(
                "{} raised in shadow mode (nothing blocked) for brand workspace {} on a {}",
                OFF_PLATFORM_AUDIT_EVENT,
                ctx.brandWorkspaceId(),
                target);
        auditLogService.recordRiskSignal(
                ctx.brandWorkspaceId(),
                OFF_PLATFORM_AUDIT_EVENT,
                OFF_PLATFORM_AUDIT_REASON,
                Map.<String, Object>of("target", target, "blocking", false));
    }

    // ------------------------------------------------------------------
    // Loaders
    // ------------------------------------------------------------------

    private CreatorProfile requireProfile(String creatorProfileId) {
        return creatorProfileRepository
                .findById(creatorProfileId)
                .orElseThrow(
                        () ->
                                new ApiException(
                                        "CREATOR_PROFILE_NOT_FOUND", "Creator profile not found", HttpStatus.NOT_FOUND));
    }

    /**
     * The extraction-shaped view of a live deal (SPEC.md &sect;5.1). Only the fields a collaboration
     * actually carries are populated; the extractor-only hints ({@code off_platform_payment_hint},
     * {@code disclosure_hidden_hint}, {@code vague_deliverables}, {@code claims},
     * {@code regulated_category}) stay at their defaults, so on this path those rules fire from the
     * deal's text alone.
     *
     * <p><b>{@code usage_channels} is COMMA-separated on {@link Collaboration}, not JSON.</b> The
     * entity's own javadoc says so, {@code DealService.applyDealTermsIfPresent} writes it with
     * {@code String.join(",", ...)} and {@code DealService.toDealTermsDto} reads it back with
     * {@code split(",")}. SPEC.md &sect;5.1 calls it a JSON string; parsing it with
     * {@code JsonLists.stringListFromJson} would not throw — that method swallows the parse error
     * and returns an empty list — so {@code USAGE_PERPETUAL}'s "all five channels" test would
     * silently never fire on any deal. {@code exclusivity_brands} IS JSON, and is parsed as such.
     *
     * <p><b>{@code deliverables} does not come from the {@code Deliverable} table alone.</b> Those
     * rows are materialised at contract time, so a deal in {@code INVITED}, {@code APPLIED} or
     * {@code IN_NEGOTIATION} has none and this view used to report zero deliverables even when the
     * latest proposal card named an exact package. {@code onTheTable} is
     * {@link #packageOnTheTable}'s answer — the rows when they exist, else the card — and it is the
     * same list {@link #quoteFor} prices, so the package the rules see and the package the creator
     * is quoted a floor for are one thing. It stays EMPTY for an absent, empty or unreadable card;
     * see {@link #packageOnTheTable} for why nothing may be substituted in its place.
     *
     * @param onTheTable the package actually on the table, already resolved by
     *     {@link #packageOnTheTable} — never re-resolved here, so there is one answer per evaluation
     */
    private static BriefExtraction viewOf(
            Collaboration collaboration,
            Campaign campaign,
            Workspace workspace,
            List<Deliverable> deliverables,
            List<DeliverableSlot> onTheTable) {
        BigDecimal budget =
                collaboration.getAgreedRate() != null
                        ? collaboration.getAgreedRate()
                        : campaign == null ? null : campaign.getBudgetMax();
        return new BriefExtraction(
                brandNameOf(null, null, campaign, workspace),
                null,
                campaign == null ? null : campaign.getEndBrandCategory(),
                deliverableLines(deliverables, onTheTable),
                budget,
                budget != null,
                false,
                null,
                campaign == null || campaign.getEndDate() == null ? null : campaign.getEndDate().toString(),
                collaboration.getUsageMonths(),
                collaboration.isUsagePerpetual(),
                commaSeparated(collaboration.getUsageChannels()),
                collaboration.getExclusivityDays(),
                collaboration.getExclusivityScope() == null ? null : collaboration.getExclusivityScope().name(),
                JsonLists.stringListFromJson(collaboration.getExclusivityBrands()),
                collaboration.getMaxRevisions(),
                null,
                false,
                false,
                null,
                null,
                false,
                null);
    }

    /**
     * The creator's OTHER collaborations, joined to the campaign fields the exclusivity and
     * calendar rules read. Three batch queries regardless of how many deals she has — campaigns,
     * workspaces and deliverables are each fetched once by id set, not per row.
     */
    private List<ActiveDeal> activeDealsFor(CreatorProfile profile, String excludeCollaborationId) {
        if (profile == null) {
            return List.of();
        }
        List<Collaboration> collaborations =
                collaborationRepository.findByCreatorId(profile.getUserId()).stream()
                        .filter(c -> excludeCollaborationId == null || !excludeCollaborationId.equals(c.getId()))
                        .toList();
        if (collaborations.isEmpty()) {
            return List.of();
        }

        Map<String, Campaign> campaigns = new LinkedHashMap<>();
        campaignRepository
                .findAllById(collaborations.stream().map(Collaboration::getCampaignId).distinct().toList())
                .forEach(campaign -> campaigns.put(campaign.getId(), campaign));
        Map<String, Workspace> workspaces = new LinkedHashMap<>();
        workspaceRepository
                .findAllById(
                        campaigns.values().stream()
                                .map(Campaign::getWorkspaceId)
                                .filter(java.util.Objects::nonNull)
                                .distinct()
                                .toList())
                .forEach(workspace -> workspaces.put(workspace.getId(), workspace));
        Map<String, Integer> deliverableCounts = new LinkedHashMap<>();
        deliverableRepository
                .findByCollaborationIdIn(collaborations.stream().map(Collaboration::getId).toList())
                .forEach(d -> deliverableCounts.merge(d.getCollaborationId(), 1, Integer::sum));

        List<ActiveDeal> deals = new ArrayList<>(collaborations.size());
        for (Collaboration c : collaborations) {
            Campaign campaign = campaigns.get(c.getCampaignId());
            Workspace workspace =
                    campaign == null ? null : workspaces.get(campaign.getWorkspaceId());
            deals.add(
                    new ActiveDeal(
                            c.getId(),
                            c.getStatus(),
                            c.getAppliedAt(),
                            c.getExclusivityDays(),
                            c.getExclusivityScope(),
                            JsonLists.stringListFromJson(c.getExclusivityBrands()),
                            brandNameOf(null, null, campaign, workspace),
                            campaign == null ? null : campaign.getEndBrandCategory(),
                            campaign == null ? null : campaign.getEndDate(),
                            c.getAgreedRate(),
                            deliverableCounts.getOrDefault(c.getId(), 0)));
        }
        return deals;
    }

    private Workspace workspaceOf(Campaign campaign) {
        if (campaign == null || campaign.getWorkspaceId() == null) {
            return null;
        }
        return workspaceRepository.findById(campaign.getWorkspaceId()).orElse(null);
    }

    /**
     * The brand's display name, most specific first: the campaign's end-brand (set when an agency
     * runs the campaign for someone else), then the brand workspace's own name, then the
     * extractor's guess, then the brief's stored guess. Never a placeholder like "Brand" — a
     * language model repeats a placeholder as a fact.
     */
    private static String brandNameOf(
            BriefExtraction extraction, CreatorBrief brief, Campaign campaign, Workspace workspace) {
        if (campaign != null && !RiskText.blank(campaign.getEndBrandName())) {
            return campaign.getEndBrandName().trim();
        }
        if (workspace != null && !RiskText.blank(workspace.getName())) {
            return workspace.getName().trim();
        }
        if (extraction != null && !RiskText.blank(extraction.brandName())) {
            return extraction.brandName().trim();
        }
        if (brief != null && !RiskText.blank(brief.getBrandNameGuess())) {
            return brief.getBrandNameGuess().trim();
        }
        return null;
    }

    /**
     * Everything on the deal a regex rule should be allowed to read: the application/invitation
     * note, the free-text usage-rights blob, and the BRAND's messages. The creator's own messages
     * are excluded on purpose — "he asked me to pay by UPI" written by the creator is her
     * describing the problem, not the brand committing it, and flagging it would put her own words
     * behind a fraud signal.
     */
    private static String dealText(Collaboration collaboration, List<DealMessage> messages) {
        StringBuilder text = new StringBuilder();
        appendIfPresent(text, collaboration.getNotes());
        appendIfPresent(text, collaboration.getUsageRights());
        for (DealMessage message : messages) {
            if (message.getSenderType() == DealSenderType.brand) {
                appendIfPresent(text, message.getContent());
            }
        }
        return text.isEmpty() ? null : text.toString();
    }

    private static void appendIfPresent(StringBuilder builder, String value) {
        if (!RiskText.blank(value)) {
            if (!builder.isEmpty()) {
                builder.append('\n');
            }
            builder.append(value);
        }
    }

    private static String lastBrandMessage(List<DealMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            DealMessage message = messages.get(i);
            if (message.getSenderType() == DealSenderType.brand && !RiskText.blank(message.getContent())) {
                return message.getContent();
            }
        }
        return null;
    }

    /**
     * The view's deliverable lines: the contract rows when a contract exists, else the package named
     * on the latest proposal card.
     *
     * <p>Both branches end in SPEC.md &sect;2.11's canonical names — the rows through
     * {@link #quoteType}, the card's free strings through
     * {@link QuoteDeliverableType#parse(String)} — so the same package produces the same lines
     * whichever side of contract drafting it is read from, and {@link Floors} folds them onto the
     * creator's three stored floors by one rule.
     *
     * <p><b>Empty in, empty out.</b> No contract and no readable card yields an empty list, which is
     * what {@code VAGUE_DELIVERABLES} reads as "nobody has said how much work this is" — the signal
     * this method exists to make true rather than to suppress.
     */
    private static List<DeliverableLine> deliverableLines(
            List<Deliverable> deliverables, List<DeliverableSlot> onTheTable) {
        List<DeliverableLine> fromContract = deliverableLines(deliverables);
        return fromContract.isEmpty() ? linesFromSlots(onTheTable) : fromContract;
    }

    /**
     * The proposal card's slots as counted view lines, canonicalised through
     * {@link QuoteDeliverableType#parse(String)} — the same fold the pricing engine applies to the
     * same strings, so the view and the quote agree on what the card named.
     *
     * <p>{@code parse} answers {@link QuoteDeliverableType#OTHER} for anything it does not
     * recognise rather than throwing, which is deliberate: a brand that typed "IG reel" has still
     * named a piece of work, and dropping it would read as "nobody said", i.e. the bug this fallback
     * fixes in the other direction.
     */
    private static List<DeliverableLine> linesFromSlots(List<DeliverableSlot> onTheTable) {
        if (onTheTable == null || onTheTable.isEmpty()) {
            return List.of();
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (DeliverableSlot slot : onTheTable) {
            if (slot == null || slot.type() == null) {
                continue;
            }
            // Both producers guarantee a positive qty (the row branch writes 1,
            // slotsFromProposalMetadata clamps with Math.max(1, ...)); this is the null guard on a
            // boxed field, and a non-positive count is not a count -- see hasCountedDeliverable.
            Integer qty = slot.qty();
            if (qty == null || qty <= 0) {
                continue;
            }
            counts.merge(QuoteDeliverableType.parse(slot.type()).name(), qty, Integer::sum);
        }
        return counts.entrySet().stream()
                .map(entry -> new DeliverableLine(entry.getKey(), entry.getValue()))
                .toList();
    }

    /**
     * Persisted {@link DeliverableType} values folded onto the brief/quote taxonomy of SPEC.md
     * &sect;2.11, then counted. The persisted enum is platform-shaped
     * ({@code INSTAGRAM_REEL}) and the pricing one is format-shaped ({@code REEL}); this is where the
     * two meet for a CONTRACTED deal, and {@link #linesFromSlots} is where they meet before a
     * contract exists.
     */
    private static List<DeliverableLine> deliverableLines(List<Deliverable> deliverables) {
        if (deliverables == null || deliverables.isEmpty()) {
            return List.of();
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Deliverable deliverable : deliverables) {
            counts.merge(quoteType(deliverable.getType()), 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .map(entry -> new DeliverableLine(entry.getKey(), entry.getValue()))
                .toList();
    }

    private static String quoteType(DeliverableType type) {
        if (type == null) {
            return "OTHER";
        }
        return switch (type) {
            case INSTAGRAM_REEL, FACEBOOK_REEL -> "REEL";
            case INSTAGRAM_STORY -> "STORY_SET";
            case INSTAGRAM_POST, INSTAGRAM_CAROUSEL, FACEBOOK_POST -> "STATIC_POST";
            case YOUTUBE_SHORT, TIKTOK_VIDEO -> "SHORT";
            case YOUTUBE_VIDEO -> "YT_INTEGRATION";
        };
    }

    /** See {@link #viewOf} — comma-separated, not JSON. Blanks are dropped, not kept as empties. */
    private static List<String> commaSeparated(String value) {
        if (RiskText.blank(value)) {
            return List.of();
        }
        return java.util.Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .toList();
    }

    /** Every flag false/null: a brief whose extraction has not landed still gets its text rules run. */
    private static BriefExtraction emptyExtraction() {
        return new BriefExtraction(
                null, null, null, List.of(), null, false, false, null, null, null, false, List.of(), null,
                null, List.of(), null, null, false, false, List.of(), null, false, List.of());
    }

    private static Locale localeFor(PreferencesResponse prefs) {
        String tag = prefs == null ? null : prefs.creatorLanguage();
        return RiskText.blank(tag) ? Rendered.DEFAULT_LOCALE : Locale.forLanguageTag(tag);
    }
}
