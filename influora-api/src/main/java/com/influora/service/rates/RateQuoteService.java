package com.influora.service.rates;

import com.influora.common.JsonLists;
import com.influora.common.Rendered;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.CreatorMetric;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.DealMessage;
import com.influora.domain.entity.MediaMetric;
import com.influora.domain.enums.CollaborationStatus;
import com.influora.domain.enums.DealMessageKind;
import com.influora.domain.enums.OfferActor;
import com.influora.domain.enums.OfferEvent;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.CollaborationRepository.RateBandCandidateRow;
import com.influora.repository.CreatorMetricsRepository;
import com.influora.repository.DealMessageRepository;
import com.influora.repository.DealOfferHistoryRepository;
import com.influora.repository.MediaMetricsRepository;
import com.influora.service.AuditLogService;
import com.influora.service.CreatorAgentPreferencesService;
import com.influora.service.scoring.CreatorTiers;
import com.influora.service.scoring.QualityScoreService;
import com.influora.service.scoring.QualityScoreService.QualityScoreResult;
import com.influora.service.scoring.RateEstimationService;
import com.influora.service.scoring.RateEstimationService.RateEstimation;
import com.influora.web.dto.brief.BriefDtos.BriefExtraction;
import com.influora.web.dto.creator.CreatorAgentDtos.PreferencesResponse;
import com.influora.web.dto.deal.DealDtos.DeliverableSlot;
import com.influora.web.dto.meera.CreatorToolDtos.AddOnLine;
import com.influora.web.dto.meera.CreatorToolDtos.PackageQuote;
import com.influora.web.dto.meera.CreatorToolDtos.QuoteLine;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * T-MEERA-CREATOR-PHASE-B (SPEC.md &sect;4.3 as corrected by &sect;14.1, B0-31) — turns a list of
 * deliverable slots into a package quote a creator can send to a brand: a unit price with an
 * honest provenance, per-line floor comparison, a bundle discount, usage add-ons, an opening ask,
 * and a recommended negotiating move.
 *
 * <p><b>&sect;14.1 wins wherever it disagrees with &sect;4.3.</b> Four corrections are built in and
 * each is load-bearing:
 *
 * <ol>
 *   <li><b>The benchmark unit is the MIDPOINT</b>, {@code min + 0.50 x (max - min)}, not the 65th
 *       percentile &sect;4.3 first specified. Opening high is the anchor's job (step 6); baking a
 *       lift into the unit too compounds it, and every line, the floor comparison and the
 *       discount all inherit the inflation.
 *   <li><b>Shrinkage at one or two own deals</b> instead of a hard three-deal cliff. A creator
 *       with one real close at 2,000 and a 3,750 benchmark quotes 2,875, not 3,750 — her own
 *       data matters from deal one, and the blend decays toward her median as deals accumulate.
 *   <li><b>The tier band needs two guards, not one.</b> Five deals is the k-anonymity floor; three
 *       distinct workspaces is what stops one brand's five deals BEING the market. And the
 *       Meera-anchored share is computed over DISTINCT collaboration ids, never a row count —
 *       {@code deal_offer_history} allows several {@code MEERA_COUNTER} rows on one negotiation,
 *       so a row count exceeds 1.0 on a two-counter deal and fires the "mostly Meera-quoted"
 *       label off a single collaboration, the exact inverse of an honesty guard.
 *   <li><b>The anchor clamp goes where a range exists.</b> Own-history and tier-band each produce
 *       a single MEDIAN and carry no upper bound, so they lift {@code x1.15} uncapped; benchmark
 *       is the only branch with a range, so it lifts {@code x1.10} clamped to that range's top.
 *       &sect;14.1.e as first written had this backwards and would not have compiled.
 * </ol>
 *
 * <p><b>Floors and preferences are read ONLY through {@link CreatorAgentPreferencesService}.</b>
 * Never {@code CreatorAgentPreferencesRepository} — the floors on that row are precisely what the
 * info barrier exists to contain, and {@code InfoBarrierTest} scans {@code service/**} for a
 * direct import.
 *
 * <p><b>Never let {@code "UNKNOWN"} reach the tier branch.</b>
 * {@code RateEstimationService.estimate()} returns the tier string {@code "UNKNOWN"} when there is
 * no metric row; it is not a {@code TIER_BASE_RATES} key and not a {@code CreatorTier} constant.
 * The tier used for the recommended move is derived from the PROFILE
 * ({@code tierOverride}, else {@link CreatorTiers#derive}), which can never produce it, and the
 * benchmark branch's {@code min > 0} guard keeps the estimate's tier out of every other path.
 * Every tier comparison here is on the STRING — {@code CreatorTier.valueOf(tier)} throws on
 * {@code "MEGA"}.
 */
@Service
public class RateQuoteService {

    /** SPEC.md &sect;14.1.f — the audit event every quote writes. */
    public static final String EVENT_RATE_QUOTE_ISSUED = "RATE_QUOTE_ISSUED";

    /** Where the quote was asked for; the {@code context} key of the audit detail map. */
    public static final String CONTEXT_CHAT = "chat";

    public static final String CONTEXT_BRIEF = "brief";
    public static final String CONTEXT_DEAL = "deal";

    public static final String MOVE_ACCEPT = "ACCEPT";
    public static final String MOVE_SCOPE_DOWN = "SCOPE_DOWN";
    public static final String MOVE_COUNTER_AT_FLOOR = "COUNTER_AT_FLOOR";
    public static final String MOVE_DECLINE = "DECLINE";

    /** SPEC.md &sect;4.3 step 8 — fixed, and deliberately says "securing funds", never "escrow". */
    public static final String PAYMENT_SCHEDULE = "50% on securing funds, 50% on delivery";

    /** SPEC.md &sect;4.3 step 8 — preferences carry no revision-rounds field; 2 is the standing default. */
    public static final int REVISION_ROUNDS = 2;

    /** SPEC.md &sect;4.3 1c, second sub-branch: no metric at all, so the creator's own floor is the unit. */
    public static final String PROVENANCE_OWN_FLOOR = "your floor";

    static final int OWN_HISTORY_DAYS = 180;

    /**
     * Public for the &sect;14.1.g calibration report, which must aggregate over the SAME window the
     * band branch sees or it describes a pricing path that does not exist.
     */
    public static final int BAND_WINDOW_DAYS = 90;

    /** At or above this many own priced deals, the median stands alone (SPEC.md &sect;14.1.c). */
    static final int OWN_MEDIAN_MIN_DEALS = 3;

    /**
     * Kabir's k-anonymity floor on the cross-tenant band (SPEC.md &sect;4.3 1b). Public for the
     * same reason as {@link #BAND_WINDOW_DAYS}: the &sect;14.1.g report suppresses a tier's
     * realised median below this exact number, and a second copy of "5" in the admin package is a
     * floor that can be lowered in one place and not the other.
     */
    public static final int BAND_MIN_DEALS = 5;

    /** SPEC.md &sect;14.1.d guard (i) — five deals from one brand are not a market. */
    static final int BAND_MIN_DISTINCT_WORKSPACES = 3;

    /** SPEC.md &sect;14.1.d guard (ii) — above this share, the band is mostly Meera quoting itself. */
    static final double MEERA_ANCHORED_LABEL_THRESHOLD = 0.5;

    static final int BUNDLE_DISCOUNT_MIN_QTY = 3;
    static final BigDecimal BUNDLE_DISCOUNT_RATE = new BigDecimal("0.10");

    /** SPEC.md &sect;14.1.a — the MIDPOINT of the benchmark range, not the 65th percentile. */
    static final BigDecimal BENCHMARK_PERCENTILE = new BigDecimal("0.50");

    /** SPEC.md &sect;14.1.e — own-history and tier-band lift, uncapped. */
    static final BigDecimal ANCHOR_LIFT_OWN = new BigDecimal("1.15");

    /** SPEC.md &sect;14.1.e — benchmark lift, clamped to the range top. */
    static final BigDecimal ANCHOR_LIFT_BENCHMARK = new BigDecimal("1.10");

    /** Matches {@code GetMyMetricsExecutor.RECENT_MEDIA_LIMIT} and {@code ScoreCalculationJob}'s window. */
    static final int RECENT_MEDIA_LIMIT = 30;

    static final String DEFAULT_CURRENCY = "INR";

    /**
     * "Accepted or completed" (SPEC.md &sect;4.3 1a). {@code CollaborationStatus} has no
     * {@code ACCEPTED} constant — {@code DealService.doAccept} transitions to
     * {@link CollaborationStatus#TERMS_AGREED}, so that is the accepted state and everything
     * downstream of it counts too. {@code CANCELLED} and {@code DISPUTED} are excluded for the
     * same reason {@code findRateBandCandidates} excludes them: a disputed rate is not a clean
     * market signal, and a cancelled one was never paid.
     */
    private static final Set<CollaborationStatus> PRICED_STATUSES =
            EnumSet.of(
                    CollaborationStatus.TERMS_AGREED,
                    CollaborationStatus.CONTRACT_PENDING,
                    CollaborationStatus.CONTRACTED,
                    CollaborationStatus.IN_PROGRESS,
                    CollaborationStatus.REVIEW_PENDING,
                    CollaborationStatus.REVISION_REQUESTED,
                    CollaborationStatus.COMPLETED);

    private final CreatorAgentPreferencesService preferencesService;
    private final CollaborationRepository collaborationRepository;
    private final DealMessageRepository dealMessageRepository;
    private final DealOfferHistoryRepository dealOfferHistoryRepository;
    private final CreatorMetricsRepository creatorMetricsRepository;
    private final MediaMetricsRepository mediaMetricsRepository;
    private final QualityScoreService qualityScoreService;
    private final RateEstimationService rateEstimationService;
    private final RateAddOns rateAddOns;
    private final RateTierProperties rateTierProperties;
    private final AuditLogService auditLogService;

    public RateQuoteService(
            CreatorAgentPreferencesService preferencesService,
            CollaborationRepository collaborationRepository,
            DealMessageRepository dealMessageRepository,
            DealOfferHistoryRepository dealOfferHistoryRepository,
            CreatorMetricsRepository creatorMetricsRepository,
            MediaMetricsRepository mediaMetricsRepository,
            QualityScoreService qualityScoreService,
            RateEstimationService rateEstimationService,
            RateAddOns rateAddOns,
            RateTierProperties rateTierProperties,
            AuditLogService auditLogService) {
        this.preferencesService = preferencesService;
        this.collaborationRepository = collaborationRepository;
        this.dealMessageRepository = dealMessageRepository;
        this.dealOfferHistoryRepository = dealOfferHistoryRepository;
        this.creatorMetricsRepository = creatorMetricsRepository;
        this.mediaMetricsRepository = mediaMetricsRepository;
        this.qualityScoreService = qualityScoreService;
        this.rateEstimationService = rateEstimationService;
        this.rateAddOns = rateAddOns;
        this.rateTierProperties = rateTierProperties;
        this.auditLogService = auditLogService;
    }

    // =========================================================================================
    // Public API (SPEC.md 4.3)
    // =========================================================================================

    /** SPEC.md &sect;4.3's declared signature; defaults the audit context to {@link #CONTEXT_CHAT}. */
    @Transactional
    public PackageQuote quote(
            CreatorProfile profile,
            PreferencesResponse prefs,
            List<DeliverableSlot> deliverables,
            List<String> addOnCodes,
            BigDecimal brandBudgetInr,
            Locale locale) {
        return quote(profile, prefs, deliverables, addOnCodes, brandBudgetInr, locale, CONTEXT_CHAT);
    }

    /**
     * @param context one of {@link #CONTEXT_CHAT}, {@link #CONTEXT_BRIEF}, {@link #CONTEXT_DEAL} —
     *     the only reason this overload exists. SPEC.md &sect;14.1.f's audit row carries where the
     *     quote was asked for, which is what makes quoted-vs-realised measurable per surface after
     *     launch, and &sect;4.3's own signature has nowhere to put it.
     */
    @Transactional
    public PackageQuote quote(
            CreatorProfile profile,
            PreferencesResponse prefs,
            List<DeliverableSlot> deliverables,
            List<String> addOnCodes,
            BigDecimal brandBudgetInr,
            Locale locale,
            String context) {
        return compute(profile, prefs, deliverables, addOnCodes, brandBudgetInr, locale, context);
    }

    /**
     * T-MEERA-CREATOR-PHASE-B Wave 3 round 2 — the quote {@code DealRiskService} hands to
     * {@code RiskContext}, so {@code BELOW_FLOOR} and {@code BARTER} compare against a priced
     * package floor instead of {@link com.influora.service.risk.Floors}' three-floor fallback.
     *
     * <p><b>Two differences from {@link #quote}, both deliberate.</b>
     *
     * <ol>
     *   <li><b>It writes no {@code RATE_QUOTE_ISSUED} row.</b> Risk flags are recomputed every time
     *       a creator opens a deal or Meera calls {@code check_deal_risks}; auditing those as
     *       issued quotes would flood the series &sect;14.1.f exists to measure (quoted versus
     *       realised) with quotes nobody was ever shown. A quote the creator actually sees still
     *       goes through {@link #quote} and is still audited.
     *   <li><b>It is {@code readOnly}</b>, because its only caller is inside a read-only risk
     *       evaluation. Nothing here writes, so joining that transaction is safe; the audit row
     *       this method does not write is the only thing that ever would have.
     * </ol>
     *
     * @param deliverablesOverride the package actually on the table when the caller knows it better
     *     than the extraction does — on the deal path the {@code Deliverable} rows, or (before a
     *     contract materialises them) the proposal card's slots via
     *     {@link #slotsFromProposalMetadata}. Null or empty falls back to the extraction's own
     *     deliverables, which is the brief path.
     */
    @Transactional(readOnly = true)
    public PackageQuote quoteForRisk(
            CreatorProfile profile,
            PreferencesResponse prefs,
            BriefExtraction extraction,
            List<DeliverableSlot> deliverablesOverride) {
        List<DeliverableSlot> slots =
                deliverablesOverride == null || deliverablesOverride.isEmpty()
                        ? slotsOf(extraction)
                        : deliverablesOverride;
        return compute(
                profile,
                prefs,
                slots,
                addOnCodesFor(extraction),
                statedBudgetOf(extraction),
                localeFor(prefs),
                null);
    }

    /**
     * @param auditContext {@link #CONTEXT_CHAT}, {@link #CONTEXT_BRIEF} or {@link #CONTEXT_DEAL} to
     *     write the {@code RATE_QUOTE_ISSUED} row, or null to compute the same quote without one —
     *     see {@link #quoteForRisk}
     */
    private PackageQuote compute(
            CreatorProfile profile,
            PreferencesResponse prefs,
            List<DeliverableSlot> deliverables,
            List<String> addOnCodes,
            BigDecimal brandBudgetInr,
            Locale locale,
            String auditContext) {
        Locale loc = locale != null ? locale : localeFor(prefs);
        List<DeliverableSlot> slots = normaliseSlots(deliverables);
        String tier = tierOf(profile);
        String currency = currencyOf(prefs);

        UnitBasis basis = resolveUnit(profile, prefs, tier);
        Priced priced = renderMoney(price(basis.unit(), slots, addOnCodes, prefs), loc);
        BigDecimal floorTotal = floorTotal(prefs, slots);

        // Step 6 - the anchor, branched on PROVENANCE (14.1.e as corrected), null under holdout.
        BigDecimal anchor = anchor(basis, priced.total(), prefs);

        // Step 7 - the move. Never reached with a null budget; never branched on a CreatorTier.
        String move = recommendedMove(brandBudgetInr, priced.total(), floorTotal, tier);
        String scopeDownOffer =
                MOVE_SCOPE_DOWN.equals(move)
                        ? scopeDownOffer(basis.unit(), slots, addOnCodes, prefs, brandBudgetInr, loc)
                        : null;

        // Step 9 - every money string is rendered here, in the creator's locale, and never in Python.
        PackageQuote quoteResult =
                new PackageQuote(
                        priced.lines(),
                        priced.addOns(),
                        Rendered.money(priced.discount(), loc),
                        priced.discount(),
                        Rendered.money(priced.total(), loc),
                        priced.total(),
                        Rendered.money(anchor, loc),
                        anchor,
                        Rendered.money(floorTotal, loc),
                        floorTotal,
                        Rendered.money(scaleToPackage(basis.rangeMin(), basis.unit(), priced.total()), loc),
                        Rendered.money(scaleToPackage(basis.rangeMax(), basis.unit(), priced.total()), loc),
                        currency,
                        PAYMENT_SCHEDULE,
                        REVISION_ROUNDS,
                        basis.provenance(),
                        basis.sample(),
                        move,
                        scopeDownOffer,
                        false,
                        null);

        if (auditContext != null) {
            recordQuoteIssued(
                    profile, tier, basis, priced.total(), anchor, currency, slots.size(), auditContext);
        }
        return quoteResult;
    }

    /**
     * SPEC.md &sect;4.3 — the brief path. Deliverables, budget and usage/exclusivity riders all come
     * from the extraction, so a pasted brief prices itself.
     *
     * <p>The budget is passed on ONLY when {@code budget_stated} is true. An extractor's inferred
     * figure is a guess, and letting a guess drive the recommended move would have Meera tell a
     * creator to accept a number the brand never wrote down.
     */
    @Transactional
    public PackageQuote quoteForExtraction(
            CreatorProfile profile, PreferencesResponse prefs, BriefExtraction extraction) {
        return compute(
                profile,
                prefs,
                slotsOf(extraction),
                addOnCodesFor(extraction),
                statedBudgetOf(extraction),
                localeFor(prefs),
                CONTEXT_BRIEF);
    }

    /** An extraction's deliverable lines as priceable slots; empty when it names none. */
    private static List<DeliverableSlot> slotsOf(BriefExtraction extraction) {
        if (extraction == null || extraction.deliverables() == null) {
            return List.of();
        }
        return extraction.deliverables().stream()
                .filter(Objects::nonNull)
                .map(d -> new DeliverableSlot(d.type(), Math.max(1, d.qty())))
                .toList();
    }

    /** See {@link #quoteForExtraction}: an inferred figure is a guess and never drives the move. */
    private static BigDecimal statedBudgetOf(BriefExtraction extraction) {
        return extraction != null && extraction.budgetStated() ? extraction.budgetInr() : null;
    }

    /**
     * SPEC.md &sect;4.3 step 5 — the sum the creator must not go below, for a caller that holds a
     * {@code creator_profiles.id} and no user id (the risk rules, the brief service).
     */
    @Transactional
    public BigDecimal floorTotal(String creatorProfileId, List<DeliverableSlot> deliverables) {
        return floorTotal(preferencesService.getByProfileId(creatorProfileId), deliverables);
    }

    /** Same computation over an already-fetched preferences row. */
    public BigDecimal floorTotal(PreferencesResponse prefs, List<DeliverableSlot> deliverables) {
        BigDecimal total = BigDecimal.ZERO;
        for (DeliverableSlot slot : normaliseSlots(deliverables)) {
            BigDecimal floor = floorFor(QuoteDeliverableType.parse(slot.type()), prefs);
            total = total.add(floor.multiply(BigDecimal.valueOf(qtyOf(slot))));
        }
        return scale(total);
    }

    // =========================================================================================
    // Step 1 - the unit price and where it came from
    // =========================================================================================

    /**
     * The single most important decision in a quote: what one reel costs, and what we are willing
     * to claim about that number. Order of provenance is 1a own history, 1b tier band, 1c
     * benchmark.
     */
    private UnitBasis resolveUnit(CreatorProfile profile, PreferencesResponse prefs, String tier) {
        // Memoised and LAZY: the benchmark costs a metrics read plus a media read, and the
        // own-history and tier-band branches do not need it. Only the 1-2 deal shrinkage blend
        // pulls it in from outside 1c.
        Supplier<UnitBasis> benchmark = memoise(() -> benchmarkUnit(profile, prefs, tier));

        List<BigDecimal> own = ownPricedUnits(profile);
        int n = own.size();
        if (n >= OWN_MEDIAN_MIN_DEALS) {
            return new UnitBasis(
                    roundRupees(median(own)), "your last " + n + " priced deals", n, null, null, false);
        }
        if (n > 0) {
            // SPEC.md 14.1.c: unit = (n x ownMedian + benchmarkUnit) / (n + 1). Rounded to whole
            // rupees the same way RateEstimationService rounds both its bounds - without that, a
            // quote and its own recomputation differ by paise and the audit row disagrees with the
            // card the creator was shown.
            BigDecimal ownMedian = median(own);
            BigDecimal blended =
                    ownMedian
                            .multiply(BigDecimal.valueOf(n))
                            .add(benchmark.get().unit())
                            .divide(BigDecimal.valueOf(n + 1L), 10, RoundingMode.HALF_UP);
            return new UnitBasis(
                    roundRupees(blended),
                    "your last " + n + " priced deals, blended with benchmark",
                    n,
                    null,
                    null,
                    false);
        }

        UnitBasis band = tierBandUnit(profile, tier);
        return band != null ? band : benchmark.get();
    }

    /**
     * SPEC.md &sect;4.3 1a — the creator's own accepted/completed deals in the last 180 days,
     * normalised to reel-equivalents.
     *
     * <p>Normalising matters: a 30,000 deal for "2 reels + 1 story" is not a 30,000 reel. The
     * deliverables live in the proposal message metadata (there is no deliverables column on
     * {@code Collaboration}), written by {@code DealService.persistProposalMessage} as a
     * {@code deliverables} array of {@code {type, qty}}. A collaboration with no readable proposal
     * metadata is treated as one reel, per &sect;4.3 1a.
     */
    private List<BigDecimal> ownPricedUnits(CreatorProfile profile) {
        Instant cutoff = Instant.now().minus(OWN_HISTORY_DAYS, ChronoUnit.DAYS);
        // Collaboration.creatorId is a users.id, not a creator_profiles.id - same resolution
        // CreatorAgentPreferencesService.computeDefaultFloor uses for the same table.
        return collaborationRepository.findByCreatorId(profile.getUserId()).stream()
                .filter(Objects::nonNull)
                .filter(c -> PRICED_STATUSES.contains(c.getStatus()))
                .filter(c -> c.getAgreedRate() != null && c.getAgreedRate().signum() > 0)
                .filter(c -> c.getUpdatedAt() != null && c.getUpdatedAt().isAfter(cutoff))
                .map(this::reelEquivalentUnit)
                .filter(Objects::nonNull)
                .sorted()
                .toList();
    }

    private BigDecimal reelEquivalentUnit(Collaboration collaboration) {
        BigDecimal weights = proposedWeight(collaboration.getId());
        if (weights.signum() <= 0) {
            return null;
        }
        return collaboration.getAgreedRate().divide(weights, 10, RoundingMode.HALF_UP);
    }

    /** Summed unit weights of the latest proposal card's deliverables; 1.0 (one reel) when absent. */
    private BigDecimal proposedWeight(String collaborationId) {
        Optional<DealMessage> proposal =
                dealMessageRepository.findFirstByCollaborationIdAndKindOrderByCreatedAtDesc(
                        collaborationId, DealMessageKind.proposal);
        List<DeliverableSlot> slots =
                proposal.map(m -> slotsFromProposalMetadata(m.getMetadataJson())).orElseGet(List::of);
        if (slots.isEmpty()) {
            return BigDecimal.ONE;
        }
        BigDecimal weight = BigDecimal.ZERO;
        for (DeliverableSlot slot : slots) {
            weight =
                    weight.add(
                            BigDecimal.valueOf(QuoteDeliverableType.parse(slot.type()).unitWeight)
                                    .multiply(BigDecimal.valueOf(qtyOf(slot))));
        }
        return weight.signum() > 0 ? weight : BigDecimal.ONE;
    }

    /**
     * Slots survive JSON round-trip as {@code List<LinkedHashMap>}, not as
     * {@code DeliverableSlot} records — the same rehydration {@code DealService.doCounter} does
     * when it carries deliverables forward from a superseded card.
     *
     * <p><b>Public because the proposal card is the only place a pre-contract deal's deliverables
     * exist.</b> {@code Deliverable} rows are materialised at contract time, so a deal in
     * {@code INVITED}, {@code APPLIED} or {@code IN_NEGOTIATION} — exactly the deals a creator is
     * deciding whether to counter — has none, and a caller that reads only that table sees an empty
     * package. {@code DealRiskService} uses this to feed {@link #quoteForRisk} the deliverables
     * actually on the table.
     *
     * @return the slots on the card, or an empty list for null, unparseable, or
     *     {@code deliverables}-free metadata — never an exception
     */
    @SuppressWarnings("unchecked")
    public static List<DeliverableSlot> slotsFromProposalMetadata(String metadataJson) {
        Map<String, Object> metadata = JsonLists.objectFromJson(metadataJson, Map.class);
        if (metadata == null || !(metadata.get("deliverables") instanceof List<?> raw)) {
            return List.of();
        }
        List<DeliverableSlot> slots = new ArrayList<>();
        for (Object item : raw) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            Object type = map.get("type");
            Object qty = map.get("qty");
            if (type == null) {
                continue;
            }
            int quantity = qty instanceof Number number ? number.intValue() : 1;
            slots.add(new DeliverableSlot(String.valueOf(type), Math.max(1, quantity)));
        }
        return slots;
    }

    /**
     * SPEC.md &sect;4.3 1b as amended by &sect;14.1.d — the platform band for this creator's niche
     * and tier, or {@code null} when it fails either honesty guard.
     *
     * <p><b>Nothing from a candidate row ever leaves this method except a median and two counts.</b>
     * That is Kabir's Phase-2 gate on {@code findRateBandCandidates}, restated: no workspace id and
     * no creator id reaches any payload, here or downstream.
     */
    private UnitBasis tierBandUnit(CreatorProfile profile, String tier) {
        String niche = primaryCategory(profile);
        if (niche == null) {
            return null;
        }
        Instant cutoff = Instant.now().minus(BAND_WINDOW_DAYS, ChronoUnit.DAYS);
        List<RateBandCandidateRow> band =
                collaborationRepository.findRateBandCandidates(niche).stream()
                        .filter(Objects::nonNull)
                        .filter(r -> r.getAgreedRate() != null && r.getAgreedRate().signum() > 0)
                        // updatedAt is nullable in the projection's contract; an unreadable
                        // timestamp drops the row rather than silently widening the 90-day claim.
                        .filter(r -> r.getUpdatedAt() != null && r.getUpdatedAt().isAfter(cutoff))
                        .filter(r -> tier.equals(CreatorTiers.derive(followersOf(r))))
                        .toList();

        if (band.size() < BAND_MIN_DEALS) {
            return null;
        }
        long distinctWorkspaces =
                band.stream()
                        .map(RateBandCandidateRow::getWorkspaceId)
                        .filter(Objects::nonNull)
                        .distinct()
                        .count();
        if (distinctWorkspaces < BAND_MIN_DISTINCT_WORKSPACES) {
            return null;
        }

        List<String> bandIds =
                band.stream()
                        .map(RateBandCandidateRow::getCollaborationId)
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList();
        String provenance = band.size() + " closed deals in your tier in 90 days";
        if (meeraAnchoredShare(bandIds) > MEERA_ANCHORED_LABEL_THRESHOLD) {
            // The feedback-loop label. It does not stop the loop; it stops the loop being
            // invisible, which is the whole point of 14.1.d.
            provenance = provenance + ", mostly Meera-quoted";
        }

        BigDecimal median =
                median(band.stream().map(RateBandCandidateRow::getAgreedRate).sorted().toList());
        return new UnitBasis(roundRupees(median), provenance, band.size(), null, null, false);
    }

    /**
     * SPEC.md &sect;14.1.d — DISTINCT collaborations holding at least one {@code MEERA_COUNTER},
     * over the DISTINCT collaborations in the band.
     *
     * <p>Both halves must be distinct-counted. {@code deal_offer_history}'s unique key is
     * {@code (collaboration_id, sequence_no)}, not {@code (collaboration_id, event)}, so a
     * negotiation with two Meera-drafted counters holds two {@code MEERA_COUNTER} rows. A row
     * count on either side pushes this above 1.0 and fires the label off a single deal. Never
     * {@code countByCollaborationIdInAndEvent}.
     *
     * <p><b>Filtered to {@link OfferActor#CREATOR}, and that filter is load-bearing.</b> A
     * Meera-drafted counter is a creator action by definition, but {@code POST /deals/{id}/counter}
     * is a MUTUAL route that a brand client also posts to. Counting brand-actor rows here let a brand
     * inflate the share that decides whether this band's price is labelled "mostly Meera-quoted" — an
     * honesty label a counterparty must not be able to set. {@code DealService.doCounter} now also
     * refuses to stamp authorship on a brand-actor row; both halves are needed, because either alone
     * leaves the number forgeable by whichever layer is skipped.
     */
    private double meeraAnchoredShare(List<String> bandIds) {
        if (bandIds.isEmpty()) {
            return 0.0;
        }
        List<String> anchored =
                dealOfferHistoryRepository.findDistinctCollaborationIdsByEvent(
                        bandIds, OfferEvent.MEERA_COUNTER, OfferActor.CREATOR);
        return anchored == null ? 0.0 : anchored.size() / (double) bandIds.size();
    }

    /**
     * SPEC.md &sect;4.3 1c as amended by &sect;14.1.a — the formula branch, and the only branch that
     * carries a range.
     */
    private UnitBasis benchmarkUnit(CreatorProfile profile, PreferencesResponse prefs, String tier) {
        Optional<CreatorMetric> latestMetric =
                creatorMetricsRepository
                        .findByCreatorProfileIdOrderByTimeDesc(profile.getId(), PageRequest.of(0, 1))
                        .stream()
                        .findFirst();

        // A real quality score, not QualityScoreResult.absent(). CreatorAgentPreferencesService's
        // default-floor path passes absent() because it runs at row-creation time before anything
        // is measured; a quote is a pricing decision the creator will send to a brand, and
        // discarding a computed signal there would systematically under-quote a high-quality
        // creator by up to 20%. Same window (30) as GetMyMetricsExecutor, so the number behind the
        // quote and the number get_my_metrics shows her come from one calculation.
        QualityScoreResult quality = QualityScoreResult.absent();
        if (latestMetric.isPresent()) {
            List<MediaMetric> recentMedia =
                    mediaMetricsRepository.findByCreatorProfileIdOrderByTimeDesc(
                            profile.getId(), PageRequest.of(0, RECENT_MEDIA_LIMIT));
            quality = qualityScoreService.calculate(latestMetric, recentMedia);
        }

        List<String> categories = JsonLists.stringListFromJson(profile.getCategoriesJson());
        RateEstimation estimate = rateEstimationService.estimate(latestMetric, quality, categories);

        BigDecimal min = estimate.min();
        BigDecimal max = estimate.max();
        if (min == null || min.signum() <= 0) {
            // No metric row: estimate() returned 0/0 and the tier string "UNKNOWN". Fall back to
            // the creator's own floor, used VERBATIM - it is her number, not one we derived, so it
            // is not rounded - and say so. This is the guard that keeps "UNKNOWN" out of every
            // downstream branch.
            BigDecimal floor =
                    prefs != null && prefs.reelFloor() != null ? prefs.reelFloor() : BigDecimal.ZERO;
            return new UnitBasis(floor, PROVENANCE_OWN_FLOOR, 0, null, null, true);
        }

        // 14.1.h - an admin override replaces the tier BASE, then estimate()'s own multipliers are
        // re-applied on top. Substituting the override for estimate()'s OUTPUT instead would
        // silently discard the engagement, category and quality signals.
        Optional<long[]> override = rateTierProperties.override(tier);
        if (override.isPresent()) {
            double factor =
                    factorOrOne(estimate.factors(), "engagementMultiplier")
                            * factorOrOne(estimate.factors(), "categoryMultiplier")
                            * factorOrOne(estimate.factors(), "qualityMultiplier");
            min = roundRupees(BigDecimal.valueOf(override.get()[0] * factor));
            max = roundRupees(BigDecimal.valueOf(override.get()[1] * factor));
        }

        return new UnitBasis(
                benchmarkUnitFor(min, max), RateAddOns.PROVENANCE_BENCHMARK, 0, min, max, true);
    }

    /**
     * SPEC.md &sect;4.3 1c as amended by &sect;14.1.a — the benchmark unit for a min/max band, and
     * the ONLY place that formula is written.
     *
     * <p>Public because the &sect;14.1.g calibration report has to show admins the same
     * {@code benchmark_unit} a live quote would derive from the tier band it is calibrating.
     * Restating {@code min + 0.50 x (max - min)} over there would let the report and the quote
     * path drift apart silently, and the report exists precisely to be trusted about what the
     * quote path does.
     *
     * @return {@code null} when either bound is null
     */
    public static BigDecimal benchmarkUnitFor(BigDecimal min, BigDecimal max) {
        if (min == null || max == null) {
            return null;
        }
        return roundRupees(min.add(BENCHMARK_PERCENTILE.multiply(max.subtract(min))));
    }

    // =========================================================================================
    // Steps 2-5 - lines, discount, add-ons, totals
    // =========================================================================================

    private Priced price(
            BigDecimal unit,
            List<DeliverableSlot> slots,
            List<String> addOnCodes,
            PreferencesResponse prefs) {
        List<QuoteLine> lines = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        int totalQty = 0;

        for (DeliverableSlot slot : slots) {
            QuoteDeliverableType type = QuoteDeliverableType.parse(slot.type());
            int qty = qtyOf(slot);
            BigDecimal unitPrice = scale(unit.multiply(BigDecimal.valueOf(type.unitWeight)));
            BigDecimal lineTotal = scale(unitPrice.multiply(BigDecimal.valueOf(qty)));
            // Step 2 - the floor comparison is per TYPE: reel_floor for a reel, story_set_floor
            // for a story set, post_floor for everything else ("everything else same as post").
            BigDecimal floor = floorFor(type, prefs);
            boolean belowFloor = floor.signum() > 0 && unitPrice.compareTo(floor) < 0;
            lines.add(
                    new QuoteLine(
                            type.name(), qty, null, unitPrice, null, lineTotal, belowFloor));
            subtotal = subtotal.add(lineTotal);
            totalQty += qty;
        }

        // Step 3 - the bundle discount, shown as the creator's concession rather than hidden in
        // the line prices, so she knows what she gave away.
        BigDecimal discount =
                totalQty >= BUNDLE_DISCOUNT_MIN_QTY
                        ? scale(subtotal.multiply(BUNDLE_DISCOUNT_RATE))
                        : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        BigDecimal discounted = scale(subtotal.subtract(discount));

        // Step 4 - add-ons compute on the DISCOUNTED subtotal, not the gross one.
        List<AddOnLine> addOns = new ArrayList<>();
        BigDecimal addOnTotal = BigDecimal.ZERO;
        for (String code : distinct(addOnCodes)) {
            Optional<RateAddOns.AddOn> addOn = rateAddOns.compute(code, discounted);
            if (addOn.isEmpty()) {
                continue;
            }
            addOns.add(
                    new AddOnLine(
                            addOn.get().code(),
                            addOn.get().label(),
                            null,
                            addOn.get().amount(),
                            addOn.get().basis()));
            addOnTotal = addOnTotal.add(addOn.get().amount());
        }

        return new Priced(lines, addOns, discount, scale(discounted.add(addOnTotal)));
    }

    /** Second pass over an already-priced package, rendering every money string in one place. */
    private Priced renderMoney(Priced priced, Locale locale) {
        List<QuoteLine> lines =
                priced.lines().stream()
                        .map(
                                l ->
                                        new QuoteLine(
                                                l.type(),
                                                l.qty(),
                                                Rendered.money(l.unitPriceValue(), locale),
                                                l.unitPriceValue(),
                                                Rendered.money(l.lineTotalValue(), locale),
                                                l.lineTotalValue(),
                                                l.belowFloor()))
                        .toList();
        List<AddOnLine> addOns =
                priced.addOns().stream()
                        .map(
                                a ->
                                        new AddOnLine(
                                                a.code(),
                                                a.label(),
                                                Rendered.money(a.amountValue(), locale),
                                                a.amountValue(),
                                                a.basis()))
                        .toList();
        return new Priced(lines, addOns, priced.discount(), priced.total());
    }

    // =========================================================================================
    // Steps 6-7 - anchor and recommended move
    // =========================================================================================

    /**
     * SPEC.md &sect;14.1.e as corrected — the clamp lives on the branch that HAS a range.
     *
     * <p>Own-history (1a) and tier-band (1b) each produce a single median with no upper bound, so
     * the clamped form is not merely wrong there, it does not compile. Benchmark (1c) is the only
     * branch carrying {@code estimate().max()}.
     */
    private BigDecimal anchor(UnitBasis basis, BigDecimal total, PreferencesResponse prefs) {
        if (prefs != null && prefs.negotiationHoldout()) {
            // The control arm sees no opening ask at all - that is what makes it a control.
            return null;
        }
        if (!basis.benchmark()) {
            return scale(total.multiply(ANCHOR_LIFT_OWN));
        }
        BigDecimal lifted = scale(total.multiply(ANCHOR_LIFT_BENCHMARK));
        BigDecimal ceiling = scaleToPackage(basis.rangeMax(), basis.unit(), total);
        return ceiling != null && ceiling.compareTo(lifted) < 0 ? ceiling : lifted;
    }

    /**
     * SPEC.md &sect;4.3 step 7. Branches on the tier STRING — {@code CreatorTier.valueOf("MEGA")}
     * throws, and {@code "MID or above"} is not a string comparison.
     *
     * <p>An unrecognised tier falls to {@link #MOVE_COUNTER_AT_FLOOR}, the conservative move: a
     * creator whose tier we cannot establish should not be told to shrink her own scope.
     */
    private static String recommendedMove(
            BigDecimal budget, BigDecimal total, BigDecimal floorTotal, String tier) {
        if (budget == null) {
            return null;
        }
        if (budget.compareTo(total) >= 0) {
            return MOVE_ACCEPT;
        }
        if (budget.compareTo(floorTotal) < 0) {
            return MOVE_DECLINE;
        }
        return CreatorTiers.NANO.equals(tier) || CreatorTiers.MICRO.equals(tier)
                ? MOVE_SCOPE_DOWN
                : MOVE_COUNTER_AT_FLOOR;
    }

    /**
     * Removes the lowest-weight unit at a time until the package fits the budget, then renders what
     * is left. Terminates because every iteration drops one unit and the loop stops at one.
     */
    private String scopeDownOffer(
            BigDecimal unit,
            List<DeliverableSlot> slots,
            List<String> addOnCodes,
            PreferencesResponse prefs,
            BigDecimal budget,
            Locale locale) {
        List<DeliverableSlot> current = new ArrayList<>(slots);
        Priced priced = price(unit, current, addOnCodes, prefs);
        while (priced.total().compareTo(budget) > 0 && totalQty(current) > 1) {
            int index = lowestWeightIndex(current);
            if (index < 0) {
                break;
            }
            DeliverableSlot slot = current.get(index);
            if (qtyOf(slot) <= 1) {
                current.remove(index);
            } else {
                current.set(index, new DeliverableSlot(slot.type(), qtyOf(slot) - 1));
            }
            priced = price(unit, current, addOnCodes, prefs);
        }
        if (current.isEmpty() || priced.total().compareTo(budget) > 0) {
            return null;
        }
        StringBuilder offer = new StringBuilder();
        for (DeliverableSlot slot : current) {
            if (offer.length() > 0) {
                offer.append(", ");
            }
            offer.append(qtyOf(slot)).append(" x ").append(QuoteDeliverableType.parse(slot.type()).name());
        }
        return offer + " for " + Rendered.money(priced.total(), locale);
    }

    private static int lowestWeightIndex(List<DeliverableSlot> slots) {
        int index = -1;
        double lowest = Double.MAX_VALUE;
        for (int i = 0; i < slots.size(); i++) {
            double weight = QuoteDeliverableType.parse(slots.get(i).type()).unitWeight;
            // Strictly-less keeps the FIRST of equally-cheap lines as the survivor's index base,
            // making the removal order deterministic for a given input order.
            if (weight < lowest) {
                lowest = weight;
                index = i;
            }
        }
        return index;
    }

    // =========================================================================================
    // Step 14.1.f - the audit row
    // =========================================================================================

    /**
     * SPEC.md &sect;14.1.f. The detail map carries <b>no floor, no floor total and no range
     * bound</b>, and not the unit either: in benchmark mode the unit is derivable straight back to
     * {@code estimate().min()}, which is the figure the creator's floor is computed from.
     * {@code InfoBarrierRuntimeTest} asserts these keys against an allow-list precisely so a future
     * "just add unit_price, it is useful" cannot leak one in.
     */
    private void recordQuoteIssued(
            CreatorProfile profile,
            String tier,
            UnitBasis basis,
            BigDecimal total,
            BigDecimal anchor,
            String currency,
            int deliverableCount,
            String context) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("tier", tier);
        detail.put("provenance", basis.provenance());
        detail.put("sample", basis.sample());
        detail.put("total", total);
        detail.put("anchor", anchor);
        detail.put("currency", currency);
        detail.put("deliverable_count", deliverableCount);
        detail.put("context", context);
        auditLogService.recordCreatorEvent(
                profile.getUserId(),
                EVENT_RATE_QUOTE_ISSUED,
                AuditLogService.OUTCOME_ALLOWED,
                detail);
    }

    // =========================================================================================
    // Helpers
    // =========================================================================================

    /**
     * Rounded to whole rupees, the way {@code RateEstimationService} rounds both its bounds. Every
     * figure this service DERIVES goes through here; a figure the creator herself set (her floor)
     * does not, because rounding her own number would change it.
     */
    public static BigDecimal roundRupees(BigDecimal value) {
        return value == null ? null : BigDecimal.valueOf(Math.round(value.doubleValue()));
    }

    /**
     * Public for the &sect;14.1.g calibration report: its realised median must be computed by the
     * SAME definition as the band median a quote is priced from, or the report is comparing a
     * benchmark against a number the quote path would not have produced.
     */
    public static BigDecimal median(List<BigDecimal> sorted) {
        if (sorted.isEmpty()) {
            return BigDecimal.ZERO;
        }
        int size = sorted.size();
        if (size % 2 == 1) {
            return sorted.get(size / 2);
        }
        return sorted.get(size / 2 - 1)
                .add(sorted.get(size / 2))
                .divide(BigDecimal.valueOf(2), 10, RoundingMode.HALF_UP);
    }

    /**
     * Scales a per-unit bound up to package size using the same factor that turned the unit into
     * the total, so the range the card shows is comparable with the total the card shows.
     */
    private static BigDecimal scaleToPackage(BigDecimal bound, BigDecimal unit, BigDecimal total) {
        if (bound == null || unit == null || unit.signum() <= 0 || total == null) {
            return null;
        }
        return scale(bound.multiply(total.divide(unit, 10, RoundingMode.HALF_UP)));
    }

    private BigDecimal floorFor(QuoteDeliverableType type, PreferencesResponse prefs) {
        if (prefs == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal floor =
                switch (type) {
                    case REEL -> prefs.reelFloor();
                    case STORY_SET -> prefs.storySetFloor();
                    default -> prefs.postFloor();
                };
        return floor == null ? BigDecimal.ZERO : floor;
    }

    /**
     * The tier the recommended move branches on. An explicit admin override wins, else it is
     * derived from the profile's follower count — never from {@code estimate().tier()}, which is
     * {@code "UNKNOWN"} for a creator with no metric row.
     */
    private static String tierOf(CreatorProfile profile) {
        return profile.getTierOverride() != null
                ? profile.getTierOverride().name()
                : CreatorTiers.derive(profile.getTotalFollowers());
    }

    private static String primaryCategory(CreatorProfile profile) {
        List<String> categories = JsonLists.stringListFromJson(profile.getCategoriesJson());
        return categories.stream()
                .filter(c -> c != null && !c.isBlank())
                .findFirst()
                .map(c -> c.trim().toUpperCase(Locale.ROOT))
                .orElse(null);
    }

    /**
     * The floors are denominated in {@code floor_currency} and every below-floor comparison here is
     * against them, so the quote is labelled in that currency.
     *
     * <p><b>There is no conversion anywhere on this path.</b> {@code RateEstimationService}
     * hard-codes {@code "INR"} and {@code Rendered.money} formats without converting, so a creator
     * whose floor currency is not INR gets INR-derived benchmark figures under a non-INR label.
     * That is a real gap, not a rounding one; it needs a rate source B0 does not have.
     */
    private static String currencyOf(PreferencesResponse prefs) {
        if (prefs == null || prefs.floorCurrency() == null || prefs.floorCurrency().isBlank()) {
            return DEFAULT_CURRENCY;
        }
        return prefs.floorCurrency();
    }

    private static Locale localeFor(PreferencesResponse prefs) {
        String tag = prefs == null ? null : prefs.creatorLanguage();
        return tag == null || tag.isBlank() ? Rendered.DEFAULT_LOCALE : Locale.forLanguageTag(tag);
    }

    /** An empty package is priced as one reel, matching &sect;4.3 1a's "if absent, treat as one reel". */
    private static List<DeliverableSlot> normaliseSlots(List<DeliverableSlot> slots) {
        if (slots == null || slots.isEmpty()) {
            return List.of(new DeliverableSlot(QuoteDeliverableType.REEL.name(), 1));
        }
        return slots.stream().filter(Objects::nonNull).filter(s -> s.type() != null).toList();
    }

    /** SPEC.md &sect;4.3 — the riders a brief's terms imply, in {@link RateAddOns#CODES} order. */
    private static List<String> addOnCodesFor(BriefExtraction extraction) {
        if (extraction == null) {
            return List.of();
        }
        Set<String> channels = new LinkedHashSet<>();
        if (extraction.usageChannels() != null) {
            extraction.usageChannels().stream()
                    .filter(Objects::nonNull)
                    .map(c -> c.trim().toUpperCase(Locale.ROOT))
                    .forEach(channels::add);
        }
        List<String> codes = new ArrayList<>();
        // Perpetuity supersedes a fixed usage window: a brief asking for both is asking for
        // perpetual rights, and charging a 30-day repost rider on top double-counts the same term.
        if (extraction.usagePerpetual()) {
            codes.add(RateAddOns.PERPETUITY);
        } else if (extraction.usageMonths() != null && extraction.usageMonths() > 0) {
            codes.add(RateAddOns.REPOST_30D);
        }
        if (channels.contains("PAID_ADS")) {
            codes.add(RateAddOns.PAID_ADS_QUARTER);
        }
        if (channels.contains("WHITELISTING")) {
            codes.add(RateAddOns.WHITELISTING);
        }
        if (extraction.exclusivityDays() != null && extraction.exclusivityDays() > 0) {
            codes.add(RateAddOns.EXCLUSIVITY_30D);
        }
        return codes;
    }

    private static List<String> distinct(List<String> codes) {
        if (codes == null) {
            return List.of();
        }
        return new ArrayList<>(
                new LinkedHashSet<>(codes.stream().filter(Objects::nonNull).toList()));
    }

    private static int qtyOf(DeliverableSlot slot) {
        return slot.qty() == null ? 1 : Math.max(1, slot.qty());
    }

    private static int totalQty(List<DeliverableSlot> slots) {
        return slots.stream().mapToInt(RateQuoteService::qtyOf).sum();
    }

    private static long followersOf(RateBandCandidateRow row) {
        return row.getTotalFollowers() == null ? 0L : row.getTotalFollowers();
    }

    private static double factorOrOne(Map<String, Object> factors, String key) {
        Object value = factors == null ? null : factors.get(key);
        return value instanceof Number number ? number.doubleValue() : 1.0;
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static <T> Supplier<T> memoise(Supplier<T> delegate) {
        return new Supplier<>() {
            private T value;
            private boolean resolved;

            @Override
            public T get() {
                if (!resolved) {
                    value = delegate.get();
                    resolved = true;
                }
                return value;
            }
        };
    }

    /**
     * The unit price plus everything we are willing to claim about where it came from.
     *
     * @param benchmark true only for step 1c — the ONLY branch that can carry a range, hence the
     *     only branch whose anchor is clamped (SPEC.md 14.1.e as corrected)
     */
    private record UnitBasis(
            BigDecimal unit,
            String provenance,
            int sample,
            BigDecimal rangeMin,
            BigDecimal rangeMax,
            boolean benchmark) {}

    /** A priced package, before or after {@link #renderMoney} fills in the display strings. */
    private record Priced(
            List<QuoteLine> lines, List<AddOnLine> addOns, BigDecimal discount, BigDecimal total) {}
}
