package com.influora.service.risk.rules;

import com.influora.service.risk.DealRiskService;
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
 * <p><b>The middle one reads the package on the table, not the contract table.</b> It used to be
 * disarmed on the deal path outright, because {@code DealRiskService.viewOf} built the deliverable
 * list from the {@code Deliverable} rows alone and those are materialised when a contract is drafted
 * — so every deal in {@code INVITED}, {@code APPLIED} or {@code IN_NEGOTIATION} looked like a brief
 * that named no count, and this flag fired on the whole population the risk panel serves. That guard
 * removed the false positive by removing the signal: a pre-contract deal with NO proposal card
 * genuinely has unspecified quantities, and that is precisely what the creator needs told.
 * {@code viewOf} now falls back to the latest proposal card through
 * {@code DealRiskService.packageOnTheTable}, so the view can tell "the card names three reels" from
 * "nobody has named anything" — and with a correct view the target guard was redundant on the first
 * case and actively wrong on the second, so it is gone.
 *
 * <p><b>What has to stay true for this branch to mean anything.</b> The view must report an EMPTY
 * list for an absent, empty or unreadable card. {@code RateQuoteService.normaliseSlots} prices an
 * empty package as a single REEL (SPEC.md &sect;4.3 1a); if that substitution ever reached the view a
 * counted line would appear where nobody named one, and this branch would go permanently dark
 * instead of firing — a worse failure than the false positive it replaced, and one no assertion about
 * an already-silent flag could see. It does not reach the view: {@code normaliseSlots} is private to
 * {@code RateQuoteService.compute}, and {@code RateQuoteService.slotsFromProposalMetadata} — the
 * resolver {@code packageOnTheTable} actually calls — returns an empty list for null, unparseable and
 * {@code deliverables}-free metadata. {@code DealRiskServiceEvaluateDealTest} asserts both halves.
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
        boolean noCounts = !hasCountedDeliverable(extraction == null ? null : extraction.deliverables());
        boolean inText = RiskText.matches(ctx.text(), VAGUE_TEXT);

        if (!flagged && !noCounts && !inText) {
            return Optional.empty();
        }

        String basis = flagged ? "STATED" : noCounts ? "NO_QUANTITIES" : "BRIEF_TEXT";
        // Wording only, not a firing condition: the source named in the sentence follows the
        // engine-stamped target, so a creator reading this on a live deal is pointed at the
        // proposal card in front of her, not a document that does not exist on that path.
        String source = DealRiskService.TARGET_DEAL.equals(ctx.target()) ? "The proposal" : "The brief";
        return Optional.of(
                new RiskFlag(
                        CODE,
                        RiskSeverity.WARN.name(),
                        TITLE,
                        source + " does not say how many pieces of content you owe.",
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
