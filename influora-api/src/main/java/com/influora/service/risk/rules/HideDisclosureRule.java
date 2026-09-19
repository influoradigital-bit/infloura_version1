package com.influora.service.risk.rules;

import com.influora.service.risk.RiskContext;
import com.influora.service.risk.RiskSeverity;
import com.influora.service.risk.RiskText;
import com.influora.web.dto.brief.BriefDtos.BriefExtraction;
import com.influora.web.dto.meera.CreatorToolDtos.RiskFlag;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * {@code HIDE_DISCLOSURE} (SPEC.md &sect;5.2) — the brand asked the creator to run the post without
 * the ad label.
 *
 * <p><b>Not dismissible</b>, per the spec. This is the one flag whose action is a refusal rather
 * than a negotiation: Meera does not draft the undisclosed version, and says so in one line. The
 * creator carries the ASCI liability, not the brand, so a dismiss button here would be a button
 * that hides her own exposure from her.
 *
 * <p>The pattern is matched against {@link RiskText#norm}'s output — which is why it catches
 * {@code don't} written with a curly apostrophe, the form every brand pasting from a document
 * actually sends, and (K-2b) an NFC-composed Devanagari nukta letter however the brand's client
 * decomposed it. AMEND-0917: SPEC.md &sect;5.2's cell for this code states the intent in words;
 * {@code HIDE_TEXT}'s javadoc, not the SPEC table, is the pattern's contract now.
 */
public final class HideDisclosureRule implements RiskRule {

    public static final String CODE = "HIDE_DISCLOSURE";
    public static final String TITLE = "Brand asked you to hide the ad label";

    /**
     * K-2b (Kabir, {@code KABIR-CONSENT-0917.md} "Last call — K-2"; Priya, {@code RULINGS-U-0917.md}
     * round 4). SPEC.md &sect;5.2's cell now states the intent in words rather than a verbatim
     * regex; the pattern's contract is {@code RiskFlagCorpusTest}, not this literal.
     *
     * <p>Replaces the original {@code (no|don'?t|without)\s+(#ad|#collab|#sponsored|disclos|paid
     * partnership)}, which had two measured false-flag defects against normal brief text (Kabir,
     * real Java 21 regex): no leading {@code \b} let {@code "no"} match inside a brand name ending
     * in those letters (TECNO, OPPO Reno, Casino, each followed by a space and a hashtag), and bare
     * {@code disclos} fired on ordinary confidentiality language ({@code "don't disclose the fee"},
     * {@code "don't disclose the launch date"}). Package-private so {@code RiskFlagCorpusTest} (same
     * package) can run the corpus directly against the rule's own pattern via {@link RiskText#matches}.
     *
     * <ol>
     *   <li>Leading <b>and</b> trailing {@code \b} around the label-omission branch, so {@code "no
     *       #adventure hashtags please"} does not fire (trailing) and {@code "TECNO #ad"} does not
     *       fire (leading) — both fired on the original pattern.
     *   <li>Bare {@code disclos} removed. Replaced by explicit label tokens ({@code #ad},
     *       {@code #collab}, {@code #sponsored}, {@code ad tag}, {@code ad label}, {@code paid
     *       partnership}, {@code sponsored tag}) and two explicit phrasings that stay meaningful
     *       without a bare stem: {@code "don't mention it's sponsored"} and {@code "don't disclose
     *       (this|it) as (a )?(paid partnership|ad)"} — the latter is required to keep {@code
     *       TRIGGER_TEXT} ({@code CreatorBriefServiceRealRiskRulesTest.java}) firing.
     *   <li>Hinglish short form: {@code (ad|sponsored|paid partnership) (mat|nahi)
     *       (likh|daal|laga|dikha|mention)...} — Latin script, so a plain {@code \b} is correct
     *       here (Kabir's probe: {@code \bad\s+mat\b} against "ad mat likhna" fires under Java's
     *       default flags). F-1769 / K-2c (round 6, Ruling 3c): the {@code #ad} alternative was
     *       pruned from this branch. F-1776 / K-2c.2 (round 7, Ruling 3; Nisha's yes/no,
     *       {@code NISHA-COMPLIANCE-ROWS-0918.md} "Short-form check"): the {@code na} alternative
     *       was pruned too — Nisha's own natural-sentence attempt at a guard row for it
     *       ({@code NISHA-GUARD-HD-F-na}) only ever used "na" as a trailing soft-request tag on a
     *       different verb ("rakhna na… mat likhna"), never as the negator sitting directly in
     *       front of the disclosure verb, and she confirmed no brand manager sends "ad na likhna"
     *       that terse way. {@code \b} needs a word character immediately to its left, so
     *       {@code \b#ad} can only ever match right after another word character (e.g. inside
     *       "caption#ad"); on every text measured, the bare {@code ad} alternative matches the same
     *       span one character later regardless, so {@code #ad} here never changed the branch's
     *       answer ({@code HashAdProbe.java}). The Devanagari branch below keeps its own
     *       {@code #ad}: its lookaround, not {@code \b}, is what bounds it, and it is the only
     *       alternative that catches "#ad मत डालना".
     *   <li>Devanagari short form, bounded with letter-and-mark lookarounds instead of {@code \b}:
     *       on the JDK this build targets, {@code \b} never fires next to a Devanagari word under
     *       the default (non-{@code UNICODE_CHARACTER_CLASS}) flags (Priya's probe,
     *       {@code jdk-21.0.9.10-hotspot}), so a Devanagari alternative inside the shared
     *       {@code \b(...)\b} group would compile, read correctly in review, and never match.
     * </ol>
     *
     * <p><b>F-1776 / K-2c.2 (round 7, Ruling 1): B3's {@code ad} widened to {@code an?}.</b> The
     * bare {@code ad} alternative in the don't-disclose branch only ever matched the ungrammatical
     * "as ad" / "as a ad" — {@code (?:a\s+)?} never admitted "an", so no row could depend on it
     * naturally, and F-1776's done_when forces either a widen or a prune here. Widened, not pruned:
     * the words before it ("don't disclose this/it as") already say this is a hide ask, an article
     * cannot add a false flag (measured: 0 corpus false flags, 0 ratchet rows lost), and pruning
     * would leave the natural English form "Don't disclose this as an ad." uncaught on the one path
     * where the text is the only control (FALLBACK). Guard: put {@code (?:a\s+)?} back, or delete
     * {@code ad}, and {@code RiskFlagCorpusTest}'s "Don't disclose this as an ad." row goes red
     * either way. Residual, recorded: "Don't disclose this as an ad-hoc payment" now flags, because
     * {@code \b} falls between {@code ad} and {@code -}; the sentence is contrived and does not
     * justify an extra lookahead that would itself need a guard row.
     *
     * <p><b>R7-A / F-1778 (round 7, HIGH): ASCI-compliance instructions no longer fire.</b> Measured
     * (Priya, {@code PriyaR7Probe} in {@code scratchpad/pk3r3/r7/}): 13 of 15 lines in which a brand
     * or agency tells the creator to KEEP, add or place the disclosure label raised this
     * non-dismissible flag — worse than the round-4 TECNO case, because it accuses the most
     * compliant brands, and boilerplate agency wording ("Please do not post without the paid
     * partnership label.") hits it hardest. None of the corpus's NO_FLAG rows had this shape: every
     * one stated compliance positively ("make sure you add the #ad tag"), never as a negative
     * instruction around the label. Four independent changes, each with its own {@code round7}
     * NO_FLAG corpus row (ids C1-C12) and each shown red on its own when removed:
     * <ol>
     *   <li>{@code without} pruned from B1's negators entirely, not merely re-scoped. In briefs,
     *       its natural use is the compliance form ("post without the paid partnership label"); the
     *       hide ask "post it without #ad" differs only by a negation before "post" that a regex
     *       cannot scope. Under round 6 &sect;3c, an alternative whose natural use is the opposite
     *       ask is a precision risk, not recall. <b>Accepted cost:</b> the text half no longer
     *       catches "Post it without the #ad tag." on its own — {@code VIK-GUARD2-HD-F-without} in
     *       {@code RiskFlagCorpusTest} moves out of the must-still-catch ratchet to a report-only
     *       known miss (same {@code source = "f0776"}, text unchanged). Nothing in this commit
     *       replaces that catch: on the AI path the model's own {@code disclosure_hidden_hint} is
     *       what would still flag such a brief; on FALLBACK it is an accepted, documented miss (the
     *       same class of trade as F-1777).
     *   <li>{@code no} &rarr; {@code (?<!with\s)no}. Clears "Content with no #ad label will be
     *       rejected." without touching any other {@code no} row.
     *   <li>A negative lookahead right after B1's label group,
     *       {@code (?!\s+(?:at\s+the\s+end|in\s+the\s+comments?|only|in\s+place)\b)}, clears four
     *       placement-instruction compliance lines — "don't put #ad at the end", "...only in the
     *       comments", "...use #ad only", "...in place of the paid partnership label" — one
     *       alternative per clause, no more. It reads only the words immediately after the single
     *       label occurrence it guards, so text elsewhere in the brief cannot switch it off
     *       (Constraint A's argument, the same as round 6's sentence cut).
     *   <li>B4 and B5's conditional-compliance exclusion sits on the general negators only:
     *       {@code (?:mat|nahi(?!\s+\w+\s+toh?\b))} and its Devanagari mirror
     *       {@code (?:मत|नहीं(?!\s+[\p{L}\p{M}]+\s+तो(?![\p{L}\p{M}])))}. These clear "Ad nahi likha
     *       toh post approve nahi hoga.", "Sponsored nahi likha toh ASCI notice aa sakta hai." and
     *       its Devanagari counterpart. The lookahead sits on {@code nahi} alone, not on
     *       {@code mat} or the whole branch — putting it on the whole branch also loses real
     *       commands that happen to use "toh" ("Ad mat likhna toh achha rahega, reach better
     *       aayegi.", "#ad मत डालना तो बेहतर है।"), which must keep flagging and do.
     * </ol>
     * <b>Residuals, recorded (accepted, not chased in this commit):</b> "No #ad, no approval." still
     * flags (its {@code no} is not preceded by "with"); "Go with no #ad this time" escapes (the same
     * lookbehind that clears the compliance line also shields this evasion); a hide ask rephrased as
     * a placement instruction escapes; see also the B3 {@code an?} residual above.
     *
     * <p>F-1776 (kabir, {@code KABIR-K2C-CHECK-0918.md} clause 5) alternative-deletion guards live in
     * {@code RiskFlagCorpusTest}'s {@code VIK-GUARD2-HD-F-*} rows: {@code put}, {@code #collab},
     * {@code ad\s*tag}, {@code sponsored\s+tag}, B2's {@code this\s+is}, B3's {@code it}. B3's
     * {@code ad} is guarded by the widened-{@code an?} row above; {@code without} is pruned under
     * R7-A rather than guarded (see above).
     *
     * <p>Matched against {@link RiskText#norm}'s output, which NFC-normalises first — a brand's
     * decomposed Devanagari nukta letter and this literal's precomposed one must agree, or the
     * Devanagari branch silently fails the same way the un-normalised text once did.
     */
    static final Pattern HIDE_TEXT =
            Pattern.compile(
                    "\\b(?:do not|don'?t|(?<!with\\s)no)\\s+(?:(?:use|add|put)\\s+)?(?:the\\s+)?"
                            + "(?:#ad|#collab|#sponsored|ad\\s*tag|ad\\s*label|paid\\s+partnership|sponsored\\s+tag)\\b"
                            + "(?!\\s+(?:at\\s+the\\s+end|in\\s+the\\s+comments?|only|in\\s+place)\\b)"
                            + "|\\bdon'?t\\s+mention\\s+(?:it'?s|this\\s+is)\\s+sponsored\\b"
                            + "|\\bdon'?t\\s+disclose\\s+(?:this|it)\\s+as\\s+(?:an?\\s+)?(?:paid\\s+partnership|ad)\\b"
                            + "|\\b(?:ad|sponsored|paid\\s+partnership)\\s+(?:mat|nahi(?!\\s+\\w+\\s+toh?\\b))\\s+(?:likh|daal|laga|dikha|mention)\\w*\\b"
                            + "|(?<![\\p{L}\\p{M}])(?:#ad|विज्ञापन|sponsored)\\s+(?:मत|नहीं(?!\\s+[\\p{L}\\p{M}]+\\s+तो(?![\\p{L}\\p{M}])))(?![\\p{L}\\p{M}])",
                    Pattern.CASE_INSENSITIVE);

    @Override
    public Optional<RiskFlag> apply(RiskContext ctx) {
        BriefExtraction extraction = ctx.extraction();
        boolean hinted = extraction != null && extraction.disclosureHiddenHint();
        boolean inText = RiskText.matches(ctx.text(), HIDE_TEXT);
        if (!hinted && !inText) {
            return Optional.empty();
        }

        return Optional.of(
                new RiskFlag(
                        CODE,
                        RiskSeverity.WARN.name(),
                        TITLE,
                        "Breaks ASCI guidelines.",
                        null,
                        "Tell the brand the paid-partnership label stays; the post cannot run without it.",
                        RiskText.data("basis", hinted ? "STATED" : "BRIEF_TEXT", "will_draft", "false"),
                        false));
    }
}
