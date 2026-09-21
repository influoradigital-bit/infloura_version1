package com.influora.service.risk;

import com.influora.web.dto.brief.BriefDtos.BriefExtraction;
import com.influora.web.dto.meera.CreatorToolDtos.PackageQuote;
import java.math.BigDecimal;

/**
 * T-MEERA-CREATOR-PHASE-B (SPEC.md &sect;5.2, first line) — "severity scales with deal value".
 *
 * <p>Two decisions the spec leaves open, fixed here because a rule engine cannot be half-specified:
 *
 * <ol>
 *   <li><b>What counts as "set".</b> A budget of {@code 0} is not a budget — it is a brief that
 *       said nothing about money and got serialised with a zero. So each candidate must be non-null
 *       AND strictly positive to win; otherwise the next one is tried. Without this a barter brief
 *       with {@code budget_inr: 0} would read as a small cash deal and quietly suppress every
 *       escalation.
 *   <li><b>Which way the scaling runs.</b> It only ever ESCALATES (see
 *       {@link RiskSeverity#scaled}). Reading it as a symmetric slide would mean a
 *       {@code BELOW_FLOOR} on a 4,000 offer arrives as a WARN, which is the exact case the flag
 *       exists for — the small deals are where a creator is most likely to accept a bad floor.
 * </ol>
 */
public final class DealValue {

    /** SPEC.md &sect;5.2: "small &lt; 10,000". Exclusive — 10,000 itself is {@link Band#MID}. */
    public static final BigDecimal SMALL_CEILING = new BigDecimal("10000");

    /** SPEC.md &sect;5.2: "mid 10,000 to 25,000; large &gt; 25,000". Inclusive top of MID. */
    public static final BigDecimal MID_CEILING = new BigDecimal("25000");

    private DealValue() {}

    /** The three bands of SPEC.md &sect;5.2. */
    public enum Band {
        SMALL,
        MID,
        LARGE
    }

    /**
     * SPEC.md &sect;5.2: {@code value = extraction.budget_inr} or {@code collaboration.agreedRate}
     * or {@code quote.total_value}, in that order.
     *
     * @return null when the deal carries no number at all — a brief with no budget, no agreed rate
     *     and no quote. Callers must treat that as {@link Band#SMALL}, not as zero.
     */
    public static BigDecimal of(RiskContext ctx) {
        BigDecimal stated = stated(ctx);
        if (stated != null) {
            return stated;
        }
        PackageQuote quote = ctx.quote();
        BigDecimal quoted = quote == null ? null : quote.totalValue();
        return positive(quoted) ? quoted : null;
    }

    /**
     * The money the OTHER SIDE has actually put on the table — the brief's stated budget, else the
     * collaboration's agreed rate — and never our own quote.
     *
     * <p><b>Why this is separate from {@link #of}.</b> &sect;5.2's value formula ends in
     * {@code quote.total_value}, and for the thing that formula is defined for — picking a severity
     * BAND — that tail is right: an unpriced brief for three reels is not a "small" deal just
     * because nobody named a number. But a rule that reports the value to the creator must not use
     * it. Once {@code RiskContext.quote} was actually wired (Wave 3 round 2) that tail became live,
     * and {@code BELOW_FLOOR} — whose sentence is literally "Offer is {value} against your floor of
     * {floor}" — started comparing our own asking price against our own floor on every brief that
     * stated no budget. A three-reel package earns the 10% bundle discount, so the total lands
     * under the floor total by construction and the flag fires, telling the creator a brand offered
     * a number no brand ever wrote down. {@code BelowFloorRule} therefore reads THIS method, and
     * only band scaling reads {@link #of}.
     *
     * @return null when neither side has named a figure
     */
    public static BigDecimal stated(RiskContext ctx) {
        BriefExtraction extraction = ctx.extraction();
        BigDecimal budget = extraction == null ? null : extraction.budgetInr();
        if (positive(budget)) {
            return budget;
        }
        BigDecimal agreed = ctx.collaboration() == null ? null : ctx.collaboration().getAgreedRate();
        return positive(agreed) ? agreed : null;
    }

    /** A missing value is the smallest band, so an unpriced brief never escalates anything. */
    public static Band bandOf(BigDecimal value) {
        if (!positive(value)) {
            return Band.SMALL;
        }
        if (value.compareTo(SMALL_CEILING) < 0) {
            return Band.SMALL;
        }
        return value.compareTo(MID_CEILING) > 0 ? Band.LARGE : Band.MID;
    }

    public static boolean positive(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0;
    }
}
