package com.influora.service.risk;

import com.influora.common.Rendered;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.enums.CollaborationStatus;
import com.influora.domain.enums.ExclusivityScope;
import com.influora.web.dto.brief.BriefDtos.BriefExtraction;
import com.influora.web.dto.creator.CreatorAgentDtos.PreferencesResponse;
import com.influora.web.dto.meera.CreatorToolDtos.PackageQuote;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/**
 * T-MEERA-CREATOR-PHASE-B (SPEC.md &sect;5.1) — the single input shape every rule in
 * {@link com.influora.service.risk.rules} reads.
 *
 * <p><b>Why one shape.</b> Two entry points feed the rules: a pasted brief (which has an
 * {@link BriefExtraction} and nothing else) and a live deal (which has a {@link Collaboration} and
 * no extraction at all). {@code DealRiskService.evaluateDeal} builds an extraction-shaped VIEW of
 * the collaboration so both arrive here identical, and almost no rule has to branch on "am I
 * looking at a brief or a deal". The two fields that genuinely only exist on the deal path — {@link
 * #collaboration} and {@link #lastBrandMessage} — are nullable, and the one rule that needs them
 * ({@code PARTNERSHIP_ADS_REQUEST}) refuses to fire when they are absent rather than guessing.
 *
 * <p><b>Where the one shape leaks, and why {@link #target} exists.</b> The view is extraction-SHAPED
 * but it is not an extraction: fields the extractor produces and a collaboration does not carry stay
 * at their defaults, so on the deal path an empty {@code deliverables} list does not mean "nobody
 * said how much work this is", it means "no contract has materialised the rows yet". A rule that
 * reads absence as evidence must therefore know which target it is on — that is what
 * {@code VAGUE_DELIVERABLES} reads {@link #isDealTarget()} for, and it is the only such rule.
 */
public record RiskContext(
        CreatorProfile profile,
        PreferencesResponse prefs,
        BriefExtraction extraction,
        /** Null on the brief path, and on a brief that is not attached to a deal. */
        Collaboration collaboration,
        /**
         * The creator's OTHER collaborations, already joined to their campaign's end-brand,
         * category and end date. Never null; never contains {@link #collaboration} itself — a deal
         * cannot conflict with its own exclusivity.
         */
        List<ActiveDeal> activeDeals,
        /**
         * The priced package, from {@code RateQuoteService.quoteForRisk}.
         *
         * <p>Populated on all three {@code DealRiskService} entry points since Wave 3 round 2, and
         * it is what {@code BELOW_FLOOR} and {@code BARTER} take their floor total from. It is
         * still <b>optional</b>, and rules must keep treating it that way: pricing degrades to null
         * whenever it cannot run (see {@code DealRiskService.quoteFor}), and a caller that builds
         * its own context is free to omit it. With no quote, {@code BELOW_FLOOR} and
         * {@code BARTER} fall back to {@link Floors} and {@link DealValue} to the stated budget or
         * agreed rate — degraded, never dark.
         */
        PackageQuote quote,
        /** Display name of the brand on the other side, or null when it cannot be resolved. */
        String brandName,
        /**
         * {@code workspaces.id} of the brand. The ONLY identifier that may reach the
         * {@code OFF_PLATFORM_HINT} audit row (SPEC.md &sect;5.2) — never the creator's name, never
         * the message text.
         */
        String brandWorkspaceId,
        /**
         * Free text the regex halves of the rules scan: the brief's raw paste, or (on the deal
         * path) the deal's notes, usage-rights blob and brand-authored messages concatenated.
         * Nullable; every rule that reads it is null-safe via {@link RiskText}.
         */
        String text,
        /**
         * The most recent brand-authored {@code DealMessage} body. Non-null ONLY on the
         * {@code evaluateDeal} path, which is exactly how {@code PARTNERSHIP_ADS_REQUEST} enforces
         * its "only in evaluateDeal" restriction without a separate flag.
         */
        String lastBrandMessage,
        Locale locale,
        Instant now,
        /**
         * Which of the two evaluation targets this is — {@code DealRiskService.TARGET_DEAL} or
         * {@code TARGET_BRIEF}.
         *
         * <p><b>Not the same question as {@link #isDealPath()}, which is why both exist.</b>
         * {@code collaboration} is non-null on {@code evaluateDeal} AND on {@code evaluateBrief}
         * for a brief the creator pasted against a live deal — the second is still a BRIEF
         * evaluation, and a rule that wants to know "is there a pasted brief behind this
         * extraction" must read the target, not the collaboration.
         *
         * <p><b>Stamped by the engine, not trusted from the caller.</b>
         * {@code DealRiskService.evaluate} overwrites this from its own {@code target} argument via
         * {@link #withTarget} before any rule runs, so it can never disagree with the target the
         * {@code OFF_PLATFORM_HINT} audit row records. A context built by hand may leave it null;
         * {@link #isDealTarget()} reads null as "not a deal", which degrades to the pre-guard
         * behaviour rather than silently taking a rule dark.
         */
        String target) {

    /** Normalises the two collection/locale fields so no rule needs a null check for them. */
    public RiskContext {
        activeDeals = activeDeals == null ? List.of() : List.copyOf(activeDeals);
        locale = locale == null ? Rendered.DEFAULT_LOCALE : locale;
        now = now == null ? Instant.now() : now;
    }

    /** This same context with {@link #target} set — see that field for why the engine stamps it. */
    public RiskContext withTarget(String evaluationTarget) {
        return new RiskContext(
                profile,
                prefs,
                extraction,
                collaboration,
                activeDeals,
                quote,
                brandName,
                brandWorkspaceId,
                text,
                lastBrandMessage,
                locale,
                now,
                evaluationTarget);
    }

    /** True when there is a collaboration behind this evaluation, whatever the target is. */
    public boolean isDealPath() {
        return collaboration != null;
    }

    /**
     * True only for {@code DealRiskService.TARGET_DEAL}. A null target reads as false so a
     * hand-built context keeps the behaviour it had before the target was carried at all.
     */
    public boolean isDealTarget() {
        return DealRiskService.TARGET_DEAL.equals(target);
    }

    /**
     * One of the creator's other collaborations, flattened with the campaign fields the exclusivity
     * and calendar rules need. Flattened rather than carrying the entities so a rule stays a pure
     * function of its input and cannot reach a repository.
     */
    public record ActiveDeal(
            String collaborationId,
            CollaborationStatus status,
            /** {@code Collaboration.appliedAt} — the clock the exclusivity window runs from. */
            Instant appliedAt,
            Integer exclusivityDays,
            ExclusivityScope exclusivityScope,
            /** Parsed from the JSON {@code collaborations.exclusivity_brands} column. */
            List<String> exclusivityBrands,
            /** {@code Campaign.endBrandName}, else the brand workspace name. */
            String brandName,
            /** {@code Campaign.endBrandCategory}. */
            String category,
            /** {@code Campaign.endDate} — what {@code CALENDAR_OVERLOAD} buckets into ISO weeks. */
            LocalDate campaignEndDate,
            BigDecimal agreedRate,
            /** Number of {@code Deliverable} rows on this collaboration; 0 when none exist yet. */
            int deliverableCount) {

        public ActiveDeal {
            exclusivityBrands = exclusivityBrands == null ? List.of() : List.copyOf(exclusivityBrands);
        }
    }
}
