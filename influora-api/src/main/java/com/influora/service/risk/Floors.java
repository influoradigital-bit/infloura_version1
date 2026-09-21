package com.influora.service.risk;

import com.influora.web.dto.brief.BriefDtos.BriefExtraction;
import com.influora.web.dto.brief.BriefDtos.DeliverableLine;
import com.influora.web.dto.creator.CreatorAgentDtos.PreferencesResponse;
import com.influora.web.dto.meera.CreatorToolDtos.PackageQuote;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

/**
 * T-MEERA-CREATOR-PHASE-B (SPEC.md &sect;5.2) — "the floor total of the deliverables", for the two
 * rules that need one ({@code BELOW_FLOOR} and {@code BARTER}).
 *
 * <p><b>This is a fallback, not a pricing engine.</b> When the caller hands the context a priced
 * {@link PackageQuote}, its {@code floor_total_value} wins outright: {@code RateQuoteService} owns
 * the deliverable taxonomy, the add-ons and the bundle maths, and a second opinion computed here
 * would be a second source of truth for the one number the creator is being told not to go under.
 * Since Wave 3 round 2 that is the normal path: {@code DealRiskService} prices every evaluation
 * through {@code RateQuoteService.quoteForRisk}. This class is what is left when that cannot run —
 * and "no quote" must not mean "no floor", or {@code BELOW_FLOOR} would go dark on the deals it
 * exists for.
 *
 * <p>The mapping below is therefore deliberately coarse: a creator has exactly three stored floors
 * (reel, story-set, post) and the brief taxonomy has eight deliverable types, so five of them fold
 * onto the nearest stored floor. Every video format folds onto the REEL floor, which UNDER-states a
 * dedicated YouTube video and so under-states the floor total — the safe direction, because it can
 * only make {@code BELOW_FLOOR} fire less often, never falsely.
 */
public final class Floors {

    private Floors() {}

    /**
     * @return the floor the whole package must clear, or {@link BigDecimal#ZERO} when the creator
     *     has no usable floors and no quote — callers must treat zero as "no opinion" and not fire
     */
    public static BigDecimal totalFor(RiskContext ctx) {
        PackageQuote quote = ctx.quote();
        if (quote != null && DealValue.positive(quote.floorTotalValue())) {
            return quote.floorTotalValue();
        }
        return fromPreferences(ctx.prefs(), ctx.extraction());
    }

    private static BigDecimal fromPreferences(PreferencesResponse prefs, BriefExtraction extraction) {
        if (prefs == null) {
            return BigDecimal.ZERO;
        }
        List<DeliverableLine> lines = extraction == null ? null : extraction.deliverables();
        if (lines == null || lines.isEmpty()) {
            // No itemised deliverables (a vague brief). One reel is the smallest package anyone
            // asks for, so the reel floor is the least the creator should clear. Returning ZERO
            // instead would silence BELOW_FLOOR on exactly the briefs VAGUE_DELIVERABLES flags.
            return orZero(prefs.reelFloor());
        }
        BigDecimal total = BigDecimal.ZERO;
        for (DeliverableLine line : lines) {
            if (line == null || line.qty() <= 0) {
                continue;
            }
            total = total.add(unitFloor(prefs, line.type()).multiply(BigDecimal.valueOf(line.qty())));
        }
        return total;
    }

    /** The stored floor a brief-taxonomy deliverable type folds onto — see the class javadoc. */
    public static BigDecimal unitFloor(PreferencesResponse prefs, String type) {
        if (prefs == null) {
            return BigDecimal.ZERO;
        }
        String normalised = type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
        return switch (normalised) {
            case "REEL", "SHORT", "YT_INTEGRATION", "YT_DEDICATED" -> orZero(prefs.reelFloor());
            case "STORY_SET" -> orZero(prefs.storySetFloor());
            // STATIC_POST, UGC_ONLY, OTHER and anything the extractor could not map confidently.
            default -> orZero(prefs.postFloor());
        };
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
