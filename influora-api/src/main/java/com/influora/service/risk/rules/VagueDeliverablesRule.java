package com.influora.service.risk.rules;

import com.influora.service.risk.RiskContext;
import com.influora.service.risk.RiskSeverity;
import com.influora.service.risk.RiskText;
import com.influora.web.dto.brief.BriefDtos.BriefExtraction;
import com.influora.web.dto.brief.BriefDtos.DeliverableLine;
import com.influora.web.dto.meera.CreatorToolDtos.RiskFlag;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * {@code VAGUE_DELIVERABLES} (SPEC.md &sect;5.2) — nobody has said how much work this is.
 *
 * <p>Fires on any of: the extractor's own {@code vague_deliverables} verdict, a deliverable list
 * that is empty or has no line with a positive quantity, or the giveaway phrases in the brief.
 *
 * <p><b>The middle one is BRIEF-ONLY, and that is a correctness fix rather than a tuning knob.</b>
 * On the deal path {@code DealRiskService.viewOf} builds the deliverable list from the
 * {@code Deliverable} table, and those rows are materialised when a contract is drafted — so every
 * deal in {@code INVITED}, {@code APPLIED} or {@code IN_NEGOTIATION} has none. Counting that as "no
 * quantities" made this flag fire on literally every deal a creator can still counter, which is the
 * whole population the risk panel serves, while the detail sentence asserted something about a brief
 * the data does not support: the proposal card may name an exact package. A flag that always fires
 * carries no information and teaches creators to dismiss the panel, so it degrades the thirteen
 * rules that are sound. The two triggers that ARE evidence regardless of target — the extractor's
 * explicit hint and vague language in the text — keep working on both.
 *
 * <p><b>Why not read the quantities from the proposal card instead.</b> Both available sources are
 * unsound for this question. {@code RiskContext.quote} cannot answer it:
 * {@code RateQuoteService.normaliseSlots} substitutes a single REEL slot for an empty package, so
 * {@code quote.lines()} carries a positive quantity even when nobody named one, and reading it would
 * take this rule dark on genuinely vague briefs too. Feeding
 * {@code RateQuoteService.slotsFromProposalMetadata} into the extraction view would answer it, but
 * that view is also what {@link com.influora.service.risk.Floors} prices from when no quote is
 * available, so it would move the floor fallback off the single reel that
 * {@code DealRiskServiceEvaluateDealTest#belowFloorStaysSilentWithoutAQuote} pins. Neither is
 * needed: with the target guard the branch cannot fire on a deal at all, so quantities read from the
 * card would change no outcome — and arming the branch on the deal path would reintroduce the false
 * positive for a negotiation that has no proposal card yet.
 *
 * <p>The action is the 48-hour follow-up's first half only. The assumption quote it mentions is a
 * Phase-C job; this rule asks the question and stops there rather than promising a follow-up that
 * nothing in this wave schedules.
 */
public final class VagueDeliverablesRule implements RiskRule {

    public static final String CODE = "VAGUE_DELIVERABLES";
    public static final String TITLE = "Deliverables are not pinned down";

    /** SPEC.md &sect;5.2, verbatim. */
    private static final Pattern VAGUE_TEXT =
            Pattern.compile(
                    "a few|some posts|until (we're|we are) happy|unlimited revisions",
                    Pattern.CASE_INSENSITIVE);

    @Override
    public Optional<RiskFlag> apply(RiskContext ctx) {
        BriefExtraction extraction = ctx.extraction();
        boolean flagged = extraction != null && extraction.vagueDeliverables();
        // Brief-only: on a deal the absent rows are a contract that has not been drafted, not a
        // brief that failed to name a count. See the class javadoc.
        boolean noCounts =
                !ctx.isDealTarget()
                        && !hasCountedDeliverable(extraction == null ? null : extraction.deliverables());
        boolean inText = RiskText.matches(ctx.text(), VAGUE_TEXT);

        if (!flagged && !noCounts && !inText) {
            return Optional.empty();
        }

        String basis = flagged ? "STATED" : noCounts ? "NO_QUANTITIES" : "BRIEF_TEXT";
        return Optional.of(
                new RiskFlag(
                        CODE,
                        RiskSeverity.WARN.name(),
                        TITLE,
                        "The brief does not say how many pieces of content you owe.",
                        null,
                        "Ask the brand for the exact count before you put a number on it.",
                        RiskText.data("basis", basis),
                        true));
    }

    /** True when at least one line names a real quantity — one {@code qty: 0} row is not a count. */
    private static boolean hasCountedDeliverable(List<DeliverableLine> lines) {
        if (lines == null || lines.isEmpty()) {
            return false;
        }
        return lines.stream().anyMatch(line -> line != null && line.qty() > 0);
    }
}
