package com.influora.service.meera.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influora.common.JsonLists;
import com.influora.domain.entity.BrandProfile;
import com.influora.repository.BrandProfileRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.CollaborationRepository.RateBandCandidateRow;
import com.influora.service.AuditLogService;
import com.influora.service.meera.BrandContextAssembler;
import com.influora.web.dto.meera.MeeraContextDtos.RateBand;
import com.influora.web.dto.meera.MeeraToolDtos.CalculateBudgetResult;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * R-tier executor (06-MEERA-PERMISSIONS-MATRIX.md row 4): "Suggest pool size + per-reel rate from
 * product price + goal." Pure computation, writes nothing to any table — the DoD explicitly
 * requires "writes nothing; returns advisory numbers" and this class has no repository
 * dependency that lets it MUTATE anything — {@link BrandProfileRepository} here is read-only,
 * used solely to re-derive {@code price_source} server-side (Kabir C1 re-confirm, see below).
 *
 * <p>The number this method returns is advisory only. It is NEVER read back by
 * {@code RequestPaymentExecutor} or any commit-tier executor — the charged amount at commit time
 * is always re-derived independently by {@code AmountDerivationService} from
 * {@code campaign_intents.product_price}, never from this suggestion (Kabir G1).
 *
 * <h2>P1-12 (2026-09-13): the per-creator rate comes from real deals, or it does not come</h2>
 *
 * <p>Until now this class computed {@code perCreatorRate = product_price × goalMultiplier}, with
 * multipliers of 6% (review) to 15% (conversion). Live evidence that killed it: a ₹5,300 product
 * with goal {@code review} quoted <b>₹318 per creator</b> for a hands-on product review. No Indian
 * creator accepts ₹318 for that, so a brand acting on the quote gets zero acceptances and
 * concludes the creator pool is dead.
 *
 * <p>The decisive part is that making the INPUT more accurate makes the OUTPUT worse: the real
 * price was ₹4,000, which yields ₹240. The defect is not the price — it is that a percentage of a
 * product price has no causal relationship to creator compensation. A ₹500 phone case and a
 * ₹50,000 laptop take a creator roughly the same effort to review; the product price is a property
 * of the product, the rate is a property of the creator and the deliverable. Refining a number
 * derived from the wrong quantity only makes it more confidently wrong.
 *
 * <p>So the product price no longer produces the rate at all. The rate is the median of real
 * {@code agreed_rate} values from {@code COMPLETED} collaborations in the brand's niche, via
 * {@code CollaborationRepository#findRateBandCandidates} aggregated by
 * {@link BrandContextAssembler#buildRateBand} behind its k-anonymity floor — the same band Meera
 * can already SEE in Block B of her prompt and, until now, could not get into this tool.
 *
 * <p><b>When there is no band, this tool refuses to quote</b> (see {@code buildNoBandRationale}).
 * That is the DEFAULT path in production today, not an edge case: the k-anonymity floor needs 5
 * distinct creators AND 5 distinct workspaces of completed, settled deals in one follower tier of
 * one niche, and the platform does not have that yet for most niches. Falling back to the old
 * multiplier would mean the fix changes nothing for almost every real brand, so there is no
 * fallback. An honest question ("what do you usually pay a creator?") costs one conversational
 * turn; a confident wrong number costs the brand a campaign that nobody accepts, and costs us the
 * brand's belief that our creator pool is real. The brand's own answer is also a better anchor
 * than anything we could synthesize.
 *
 * <p>{@code price_source} is still re-derived server-side and still travels on the result — the
 * price may be discussed even when it is not quoted against (Kabir C1 is untouched below).
 */
@Service
public class CalculateBudgetExecutor {

    private static final int DEFAULT_CREATOR_COUNT = 5;

    /** {@code rateBasis} — the suggestion is the median of real completed-collaboration rates. */
    static final String RATE_BASIS_BAND = "platform_rate_band";

    /**
     * {@code rateBasis} — no real band was available, so no number is quoted. This is the expected
     * production path until the platform accumulates enough settled deals per niche.
     */
    static final String RATE_BASIS_INSUFFICIENT = "insufficient_data";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AuditLogService auditLogService;
    private final BrandProfileRepository brandProfileRepository;
    private final CollaborationRepository collaborationRepository;
    private final BrandContextAssembler brandContextAssembler;

    public CalculateBudgetExecutor(
            AuditLogService auditLogService,
            BrandProfileRepository brandProfileRepository,
            CollaborationRepository collaborationRepository,
            BrandContextAssembler brandContextAssembler) {
        this.auditLogService = auditLogService;
        this.brandProfileRepository = brandProfileRepository;
        this.collaborationRepository = collaborationRepository;
        this.brandContextAssembler = brandContextAssembler;
    }

    public CalculateBudgetResult execute(String workspaceId, Map<String, Object> input) {
        // Field names match app/tools/schemas.py CALCULATE_BUDGET input_schema exactly:
        // product_price (required, number), goal (required enum: awareness|launch|conversion|review).
        // creator_count is NOT part of the schema but tolerated if present (extra fields ignored
        // elsewhere are simply absent here) since it lets a future schema revision widen this
        // executor without a Spring-side change.
        BigDecimal productPrice = decimalArg(input, "product_price");
        String goal = stringArg(input, "goal");
        Integer creatorCount = intArg(input, "creator_count");
        int effectiveCreatorCount = creatorCount != null && creatorCount > 0 ? creatorCount : DEFAULT_CREATOR_COUNT;

        // C1 re-confirm (Kabir P1-B re-audit, 2026-07-21): `price_source` is NEVER read from the
        // tool-call `input` anymore -- schemas.py no longer even declares the field. A model could
        // otherwise self-certify a guessed price as "scraped" and suppress the caveat below, which
        // is exactly the defeat Kabir demonstrated. Instead we re-derive provenance from SERVER
        // state: look up this workspace's persisted BrandProfile.productCatalogJson (the same
        // catalog Python wrote at analyze-site time, forwarded verbatim by
        // AnalyzeSiteTriggerService/BrandContextAssembler) and match the entry whose price equals
        // the product_price this tool call is computing against. Fail-safe: no BrandProfile, no
        // catalog, unparsable JSON, or no matching entry -> "inferred" (never "scraped") -- unknown
        // provenance is never assumed to be a verified fact.
        BrandProfile brandProfile =
                workspaceId == null
                        ? null
                        : brandProfileRepository.findByWorkspaceId(workspaceId).orElse(null);

        String priceSource = resolvePriceSourceFromServerState(brandProfile, productPrice);
        boolean priceIsScraped = "scraped".equalsIgnoreCase(priceSource);
        String priceConfidence = priceIsScraped ? "scraped" : "inferred";

        // P1-12: the rate comes from real completed collaborations, not from the product price.
        // Same niche resolution MeeraContextService uses for the outcome digest (first niche tag),
        // and the SAME k-anonymity aggregation — buildRateBand is called, never reimplemented.
        RateBand band = resolveRateBand(brandProfile);

        BigDecimal perCreatorRate = null;
        BigDecimal poolTotal = null;
        BigDecimal rateMin = null;
        BigDecimal rateMax = null;
        Integer rateSampleSize = null;
        String rateNiche = null;
        String currency = "INR";
        String rateBasis;
        String rationale;

        if (band == null) {
            // THE DEFAULT PRODUCTION PATH — see class javadoc. No number, on purpose.
            rateBasis = RATE_BASIS_INSUFFICIENT;
            rationale = buildNoBandRationale(effectiveCreatorCount);
        } else {
            rateBasis = RATE_BASIS_BAND;
            // Median, not mean: the candidate pool is small by construction (the k-anon floor is
            // only 5) and one outsized brand deal would drag a mean somewhere no creator in the
            // sample actually sits. min/max ship alongside so Meera quotes a RANGE and never
            // implies the median is a fixed price.
            perCreatorRate = band.median();
            rateMin = band.min();
            rateMax = band.max();
            rateSampleSize = band.sampleSize();
            rateNiche = band.niche();
            if (band.currency() != null && !band.currency().isBlank()) {
                currency = band.currency();
            }
            poolTotal =
                    perCreatorRate == null
                            ? null
                            : perCreatorRate.multiply(BigDecimal.valueOf(effectiveCreatorCount));
            rationale = buildBandRationale(band, effectiveCreatorCount, goal, currency);
        }

        // Kabir C1 caveat — but it only means the same thing on the path where a price may be
        // spoken at all. On the BAND path the rate is real and the price may legitimately be
        // mentioned alongside it, so hedge it. On the REFUSAL path there is no quote to attach a
        // price to, and the only price in play is the one the MODEL passed in as `product_price`
        // (analyze_site has never populated a real catalog in production, so priceIsScraped is
        // false by default). Handing the model "phrase this as based on an estimated price" there
        // is an invitation to open with "based on an estimated price around ₹5,300" — a number it
        // invented, laundered through a tool call, and hedged into sounding server-sourced. That is
        // the surviving half of the P1-12 defect, so the refusal path forbids the price instead.
        if (productPrice != null && !priceIsScraped) {
            rationale +=
                    RATE_BASIS_INSUFFICIENT.equals(rateBasis)
                            ? " The product price in this call is NOT a confirmed price — it came"
                                    + " from the caller, not from a verified scraped catalog. Since"
                                    + " no rate is quoted, do NOT state that price to the brand"
                                    + " either, hedged or otherwise: quote NO rupee figure in this"
                                    + " reply."
                            : " Note: this product price is an ESTIMATE, not a confirmed scraped"
                                    + " price — phrase this to the brand as based on an estimated"
                                    + " price, not a quoted fact.";
        }

        auditLogService.recordToolCall(
                workspaceId,
                "calculate_budget",
                "R",
                AuditLogService.OUTCOME_ALLOWED,
                null,
                null,
                null,
                Map.of(
                        "advisory",
                        true,
                        "price_confidence",
                        priceConfidence,
                        "rate_basis",
                        rateBasis));

        return new CalculateBudgetResult(
                poolTotal,
                perCreatorRate,
                effectiveCreatorCount,
                currency,
                rationale,
                priceConfidence,
                rateBasis,
                rateMin,
                rateMax,
                rateSampleSize,
                rateNiche);
    }

    /**
     * Resolves this brand's niche rate band, or {@code null}. Three independent doors lead to
     * {@code null} and all three are ordinary in production today: (1) no brand profile or no
     * {@code niche_tags} — {@code analyze_site} populates those and has never succeeded in
     * production, so this is currently the common one; (2) no {@code COMPLETED} collaborations in
     * that niche; (3) the k-anonymity floor not met. Door (1) short-circuits BEFORE the
     * cross-tenant query runs — the most security-sensitive query in the design is never executed
     * speculatively.
     */
    private RateBand resolveRateBand(BrandProfile brandProfile) {
        if (brandProfile == null) {
            return null;
        }
        List<String> nicheTags = JsonLists.stringListFromJson(brandProfile.getNicheTagsJson());
        String niche = nicheTags.isEmpty() ? null : nicheTags.get(0);
        if (niche == null || niche.isBlank()) {
            return null;
        }
        List<RateBandCandidateRow> candidates = collaborationRepository.findRateBandCandidates(niche);
        return brandContextAssembler.buildRateBand(niche, candidates);
    }

    /**
     * What Meera is told when there is no band. Phrased as an INSTRUCTION to ask, not as a hedge
     * around a number, because a hedge next to a figure loses — brands read the number and skip
     * the footnote, which is exactly how ₹318 reached a brand's screen with "this is an ESTIMATE"
     * written directly underneath it.
     */
    private static String buildNoBandRationale(int creatorCount) {
        return "NO RATE QUOTED — this is deliberate, do not invent one. We do not yet have enough"
                + " settled collaborations in this brand's niche to know what creators actually"
                + " charge, and a percentage of the product price is not a substitute (it bears no"
                + " relationship to creator compensation). Do NOT state, estimate, hint at or"
                + " bracket a per-creator rate or a pool total. Instead ask the brand what they"
                + " usually pay a creator for one collaboration, or what total budget they have in"
                + " mind, and work from their answer. A working assumption of "
                + creatorCount
                + " creators is fine to mention; the money is not.";
    }

    /**
     * What Meera is told when a real band exists. States the UNIT explicitly: {@code agreed_rate}
     * is the whole-collaboration figure for one creator — {@code ContractService} binds the sum of
     * ALL milestone amounts to not exceed it — so it covers everything that deal's creator
     * delivers. Quoting it as a per-reel price would be a new wrong number rather than a fix, so
     * the wording forbids that reading outright.
     */
    private static String buildBandRationale(
            RateBand band, int creatorCount, String goal, String currency) {
        return "Per-creator figure is the MEDIAN of real agreed rates from completed collaborations"
                + " in '"
                + band.niche()
                + "' ("
                + currency
                + " "
                + band.min()
                + "–"
                + band.max()
                + " across "
                + band.sampleSize()
                + " creators). Quote it as a RANGE, not a fixed price. This is a whole-collaboration"
                + " rate per creator — everything that creator delivers for the deal — NOT a"
                + " per-reel or per-deliverable rate; do not describe it as one. It is NOT derived"
                + " from the product price, so do not present it as a percentage of anything. Goal"
                + " '"
                + (goal != null ? goal : "unspecified")
                + "' may justify moving within the range; scaled to "
                + creatorCount
                + " creators for the pool. The charged amount at commit is always re-derived by"
                + " Spring and may differ from this advisory figure.";
    }

    /**
     * Re-derives {@code price_source} from persisted server state instead of trusting the model's
     * tool-call input (Kabir C1 re-confirm). Matches the tool call's {@code product_price} against
     * this workspace's persisted {@code BrandProfile.productCatalogJson} entries by numeric price
     * equality (the schema carries no product name/slug to match on more precisely). Returns
     * {@code "inferred"} whenever a match can't be established with confidence — missing profile,
     * missing/blank catalog, unparsable JSON, no price on the call, or no matching entry.
     *
     * <p>P1-12 takes the already-fetched {@link BrandProfile} as a parameter instead of looking it
     * up again: the caller now needs the same profile for {@code niche_tags}, and two lookups of
     * one row per tool call could also drift apart mid-call. The guard itself is UNCHANGED —
     * provenance still comes only from persisted server state, never from the tool input.
     */
    @SuppressWarnings("unchecked")
    private String resolvePriceSourceFromServerState(
            BrandProfile brandProfile, BigDecimal productPrice) {
        if (productPrice == null) {
            return "inferred";
        }
        if (brandProfile == null) {
            return "inferred";
        }
        String catalogJson = brandProfile.getProductCatalogJson();
        if (catalogJson == null || catalogJson.isBlank()) {
            return "inferred";
        }
        List<Map<String, Object>> catalog;
        try {
            catalog = MAPPER.readValue(catalogJson, List.class);
        } catch (Exception e) {
            return "inferred";
        }
        if (catalog == null) {
            return "inferred";
        }
        for (Map<String, Object> entry : catalog) {
            if (entry == null) {
                continue;
            }
            BigDecimal entryPrice = decimalArg(entry, "price");
            if (entryPrice != null && entryPrice.compareTo(productPrice) == 0) {
                Object entrySource = entry.get("price_source");
                String entrySourceStr = entrySource == null ? null : String.valueOf(entrySource);
                return "scraped".equalsIgnoreCase(entrySourceStr) ? "scraped" : "inferred";
            }
        }
        return "inferred";
    }

    // multiplierForGoal (0.06 review / 0.08 awareness / 0.12 launch / 0.15 conversion) was DELETED
    // in P1-12, not merely bypassed. Leaving it in place as an unused fallback is how it would come
    // back: the next person to hit the null-band path sees a ready-made "reasonable" number sitting
    // right there. There is no percentage of a product price that is a valid creator rate, so there
    // is nothing here to fall back TO. The `goal` argument still shapes the wording (see
    // buildBandRationale) and still gates nothing numeric.

    private static String stringArg(Map<String, Object> input, String key) {
        Object value = input == null ? null : input.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static BigDecimal decimalArg(Map<String, Object> input, String key) {
        Object value = input == null ? null : input.get(key);
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer intArg(Map<String, Object> input, String key) {
        Object value = input == null ? null : input.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
