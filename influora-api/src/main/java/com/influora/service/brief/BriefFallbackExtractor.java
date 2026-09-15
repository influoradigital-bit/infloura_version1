package com.influora.service.brief;

import com.influora.web.dto.brief.BriefDtos.BriefExtraction;
import com.influora.web.dto.brief.BriefDtos.DeliverableLine;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * T-MEERA-CREATOR-PHASE-B (SPEC.md &sect;3.8 step 3), B0-41 — the deterministic reading of a pasted
 * brief, used when the AI extraction is unavailable.
 *
 * <p><b>Why a second extractor exists at all.</b> A creator pastes a brief once. If the model is
 * down, or her monthly brief allowance is spent, the alternative to this class is telling her the
 * platform cannot read what she just pasted — after it has already accepted the paste. The raw text
 * is persisted BEFORE any model is consulted ({@code CreatorBrief.paste}, SPEC.md &sect;2.2), so this
 * class always has something to work over, and every downstream step (risk flags, the quote, the
 * draft) runs off a {@link BriefExtraction} and cannot tell which extractor produced it.
 *
 * <p><b>The output is LABELLED, never disguised.</b> {@code CreatorBriefService} stores
 * {@code extraction_source = FALLBACK} and the response carries a {@code degraded_reason}. A
 * regex cannot tell a budget from a follower count in every brief, and a creator who is shown
 * rule-based reading as if a model had done it will trust a wrong number. The summary lines this
 * class emits say so in words too, in the first line.
 *
 * <p><b>Deliberately conservative in one direction.</b> Every field this class cannot find with
 * confidence is left null/false, which is the same "absent means unstated" contract &sect;2.11 gives
 * the AI path. The one field where a miss is dangerous rather than merely unhelpful is
 * {@code budget_inr}: {@code RateQuoteService.statedBudgetOf} reads it only when
 * {@code budget_stated} is true, and a wrong figure there would have Meera recommend a move against
 * a number the brand never wrote. So a figure is reported only when it sits next to an explicit
 * money marker ({@code INR}, {@code Rs}, {@code ₹}, "budget", "fee", "pay"), never merely because it
 * is the largest number in the text.
 *
 * <p>Stateless and side-effect free: no repository, no clock, no locale beyond case folding. Same
 * input, same output, forever — which is what makes it testable as a floor under an outage.
 */
@Component
public class BriefFallbackExtractor {

    /** {@code CreatorBrief.EXTRACTION_SOURCE_FALLBACK} is the persisted label; this is the reason. */
    public static final String SUMMARY_PREFIX = "Read without AI, so check these against the brief";

    private static final int MAX_SUMMARY_LINES = 5;
    private static final int MAX_SUMMARY_LINE_CHARS = 120;

    /**
     * Multiplier suffix on a money figure, and it is <b>word-bounded on purpose</b>. Without the
     * trailing {@code \\b} the bare {@code l} alternative matched the "l" of "live" in
     * "Budget is INR 8000, live by 2026-10-05" and scaled the fee by 100,000 -- the extractor
     * reported 800,000,000 for an 8,000 brief. Longest alternatives first so "lakhs" is not
     * consumed as "l".
     */
    private static final String UNIT =
            "\\s*(lakhs?|lacs?|crores?|cr|k|l)?\\b";

    /**
     * A money figure with an explicit currency or intent marker on one side. Three alternatives
     * rather than one optional-marker pattern, because "1000 followers" and "reach 50000" must not
     * match and an optional marker would let them.
     */
    private static final Pattern MONEY =
            Pattern.compile(
                    "(?:(?:inr|rs\\.?|₹)\\s*([0-9][0-9,\\s]*(?:\\.[0-9]{1,2})?)" + UNIT + ")"
                            + "|(?:([0-9][0-9,\\s]*(?:\\.[0-9]{1,2})?)" + UNIT + "\\s*(?:inr|rs\\.?|rupees?))"
                            + "|(?:(?:budget|fee|payout|payment|compensation|remuneration|offer)"
                            + "\\D{0,16}?([0-9][0-9,\\s]*(?:\\.[0-9]{1,2})?)" + UNIT + ")",
                    Pattern.CASE_INSENSITIVE);

    /** ISO first, because an ISO date is unambiguous and a d/m/y one is not. */
    private static final Pattern ISO_DATE = Pattern.compile("\\b(20[2-9][0-9])-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])\\b");

    private static final Pattern PERPETUAL =
            Pattern.compile("\\bperpetu|\\bin\\s+perpetuity\\b|\\bforever\\b|\\bunlimited\\s+usage\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern EXCLUSIVITY = Pattern.compile("exclusiv", Pattern.CASE_INSENSITIVE);

    /** "60 days exclusivity" and "exclusivity for 60 days" — both orders, one pattern each side. */
    private static final Pattern EXCLUSIVITY_DAYS =
            Pattern.compile(
                    "(?:([0-9]{1,4})\\s*(?:day|days|month|months|week|weeks)\\D{0,20}?exclusiv)"
                            + "|(?:exclusiv\\w*\\D{0,20}?([0-9]{1,4})\\s*(?:day|days|month|months|week|weeks))",
                    Pattern.CASE_INSENSITIVE);

    private static final Pattern USAGE_MONTHS =
            Pattern.compile(
                    "(?:([0-9]{1,3})\\s*(?:month|months)\\D{0,24}?(?:usage|rights|licen[cs]e))"
                            + "|(?:(?:usage|rights|licen[cs]e)\\D{0,24}?([0-9]{1,3})\\s*(?:month|months))",
                    Pattern.CASE_INSENSITIVE);

    private static final Pattern REVISIONS =
            Pattern.compile("([0-9]{1,2})\\s*(?:revision|revisions|round|rounds)\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern BARTER =
            Pattern.compile("\\bbarter\\b|\\bin\\s+exchange\\s+for\\s+(?:the\\s+)?product|\\bproduct\\s+only\\b|\\bfree\\s+product\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern OFF_PLATFORM =
            Pattern.compile("\\bupi\\b|\\bgpay\\b|\\bphonepe\\b|\\bpaytm\\b|\\bbank\\s+transfer\\b|\\bneft\\b|\\bimps\\b|\\bpay\\s+you\\s+directly\\b|\\boutside\\s+the\\s+platform\\b", Pattern.CASE_INSENSITIVE);

    /**
     * Hidden-disclosure detection is NEGATIVE-form only. "#ad" on its own is a brief asking FOR
     * disclosure, which is the opposite signal, so the pattern requires a negation or an explicit
     * "organic-looking" instruction beside it.
     */
    private static final Pattern DISCLOSURE_HIDDEN =
            Pattern.compile(
                    "(?:no|without|avoid|skip|don'?t\\s+(?:use|add|put))\\s*(?:the\\s*)?(?:#\\s*ad\\b|ad\\s*tag|paid\\s+partnership|disclosure|sponsored\\s+tag)"
                            + "|(?:keep|make)\\s+it\\s+(?:look(?:ing)?\\s+)?organic"
                            + "|\\bdon'?t\\s+(?:mention|disclose|tag)\\s+(?:us|the\\s+brand|that\\s+it'?s\\s+paid)",
                    Pattern.CASE_INSENSITIVE);

    private static final Pattern PAYMENT_TERMS =
            Pattern.compile("([0-9]{1,3})\\s*%\\s*(?:advance|upfront|on\\s+signing)|\\bnet\\s*([0-9]{1,3})\\b", Pattern.CASE_INSENSITIVE);

    /**
     * Deliverable counting. The order matters: {@code STORY_SET} before {@code STATIC_POST} so
     * "story post" is a story set, and "reel" before the generic "video" so a reel is not an
     * {@code OTHER}. Keys are the pricing vocabulary of {@code QuoteDeliverableType}.
     */
    private static final List<DeliverablePattern> DELIVERABLES =
            List.of(
                    new DeliverablePattern("REEL", Pattern.compile("([0-9]{1,2})?\\s*\\b(?:reels?|ig\\s+reels?)\\b", Pattern.CASE_INSENSITIVE)),
                    new DeliverablePattern("STORY_SET", Pattern.compile("([0-9]{1,2})?\\s*\\b(?:stor(?:y|ies)(?:\\s+set)?|story\\s+frames?)\\b", Pattern.CASE_INSENSITIVE)),
                    new DeliverablePattern("YT_DEDICATED", Pattern.compile("([0-9]{1,2})?\\s*\\b(?:dedicated\\s+(?:youtube\\s+)?videos?|yt\\s+dedicated)\\b", Pattern.CASE_INSENSITIVE)),
                    new DeliverablePattern("YT_INTEGRATION", Pattern.compile("([0-9]{1,2})?\\s*\\b(?:youtube\\s+integrations?|yt\\s+integrations?|integrations?)\\b", Pattern.CASE_INSENSITIVE)),
                    new DeliverablePattern("SHORT", Pattern.compile("([0-9]{1,2})?\\s*\\b(?:shorts?)\\b", Pattern.CASE_INSENSITIVE)),
                    new DeliverablePattern("UGC_ONLY", Pattern.compile("([0-9]{1,2})?\\s*\\b(?:ugc)\\b", Pattern.CASE_INSENSITIVE)),
                    new DeliverablePattern("STATIC_POST", Pattern.compile("([0-9]{1,2})?\\s*\\b(?:static\\s+posts?|feed\\s+posts?|grid\\s+posts?|posts?)\\b", Pattern.CASE_INSENSITIVE)));

    private record DeliverablePattern(String type, Pattern pattern) {}

    /**
     * Reads what can be read deterministically. Never returns null and never throws: a
     * {@link BriefExtraction} of almost entirely nulls is a valid answer meaning "this brief did not
     * say", and the flags and quote computed from it degrade the same way.
     */
    public BriefExtraction extract(String rawText) {
        String text = rawText == null ? "" : rawText;
        String lower = text.toLowerCase(Locale.ROOT);

        List<DeliverableLine> deliverables = deliverablesIn(text);
        BigDecimal money = moneyIn(text);
        boolean barterOnly = BARTER.matcher(lower).find() && money == null;
        boolean budgetStated = money != null && !barterOnly;

        Integer exclusivityDays = daysIn(EXCLUSIVITY_DAYS, text);
        boolean exclusivityMentioned = EXCLUSIVITY.matcher(lower).find();
        Integer usageMonths = firstInt(USAGE_MONTHS, text, 0, 600);
        boolean perpetual = PERPETUAL.matcher(lower).find();

        BriefExtraction extraction =
                new BriefExtraction(
                        null, // brand_name — a regex guess at a brand name is a wrong name on screen
                        null, // product
                        null, // category — CATEGORY_MULTIPLIERS keys need meaning, not keywords
                        deliverables,
                        budgetStated ? money : null,
                        budgetStated,
                        barterOnly,
                        barterOnly ? money : null,
                        isoDateIn(text),
                        perpetual ? null : usageMonths,
                        perpetual,
                        List.of(), // usage_channels — unstated is not ORGANIC, and assuming it prices low
                        exclusivityDays,
                        exclusivityMentioned ? (exclusivityDays != null ? "CATEGORY" : "NAMED_BRANDS") : null,
                        List.of(),
                        firstInt(REVISIONS, text, 0, 50),
                        paymentTermsIn(text),
                        OFF_PLATFORM.matcher(lower).find(),
                        DISCLOSURE_HIDDEN.matcher(lower).find(),
                        List.of(), // claims — a claim is a judgement about meaning, not a pattern
                        null, // regulated_category
                        deliverables.isEmpty(),
                        List.of());

        return withSummary(extraction);
    }

    /**
     * The summary the creator reads. First line always says the reading was rule-based: SPEC.md
     * &sect;14.4.a's "labelled, not disguised" is enforced in the words, not only in a field the UI
     * may or may not render.
     */
    private BriefExtraction withSummary(BriefExtraction e) {
        List<String> lines = new ArrayList<>();
        lines.add(SUMMARY_PREFIX);

        if (!e.deliverables().isEmpty()) {
            StringBuilder sb = new StringBuilder("Asks for ");
            for (int i = 0; i < e.deliverables().size(); i++) {
                if (i > 0) {
                    sb.append(" + ");
                }
                DeliverableLine line = e.deliverables().get(i);
                sb.append(line.qty()).append(' ').append(line.type().toLowerCase(Locale.ROOT).replace('_', ' '));
            }
            lines.add(clip(sb.toString()));
        } else {
            lines.add("No specific deliverables found in the text");
        }

        if (e.budgetStated() && e.budgetInr() != null) {
            lines.add(clip("Budget found in the text: " + e.budgetInr().toPlainString()));
        } else if (e.barterOnly()) {
            lines.add("Looks like barter — no cash fee found");
        } else {
            lines.add("No budget found in the text");
        }

        if (e.deadline() != null) {
            lines.add(clip("Deadline " + e.deadline()));
        }
        if (e.usagePerpetual()) {
            lines.add("Mentions perpetual usage rights");
        } else if (e.usageMonths() != null) {
            lines.add(clip(e.usageMonths() + " months usage rights"));
        }
        if (lines.size() < MAX_SUMMARY_LINES && e.exclusivityDays() != null) {
            lines.add(clip(e.exclusivityDays() + " days exclusivity"));
        } else if (lines.size() < MAX_SUMMARY_LINES && e.exclusivityScope() != null) {
            lines.add("Mentions exclusivity without a clear duration");
        }
        if (lines.size() < MAX_SUMMARY_LINES && e.offPlatformPaymentHint()) {
            lines.add("Mentions paying you outside a platform");
        }
        if (lines.size() < MAX_SUMMARY_LINES && e.disclosureHiddenHint()) {
            lines.add("Asks you not to disclose the partnership");
        }

        List<String> capped = lines.subList(0, Math.min(lines.size(), MAX_SUMMARY_LINES));
        return new BriefExtraction(
                e.brandName(),
                e.product(),
                e.category(),
                e.deliverables(),
                e.budgetInr(),
                e.budgetStated(),
                e.barterOnly(),
                e.barterMrpInr(),
                e.deadline(),
                e.usageMonths(),
                e.usagePerpetual(),
                e.usageChannels(),
                e.exclusivityDays(),
                e.exclusivityScope(),
                e.exclusivityBrands(),
                e.maxRevisions(),
                e.paymentTerms(),
                e.offPlatformPaymentHint(),
                e.disclosureHiddenHint(),
                e.claims(),
                e.regulatedCategory(),
                e.vagueDeliverables(),
                List.copyOf(capped));
    }

    private static String clip(String line) {
        return line.length() <= MAX_SUMMARY_LINE_CHARS ? line : line.substring(0, MAX_SUMMARY_LINE_CHARS);
    }

    /**
     * The largest money figure that carries an explicit marker. Largest rather than first because a
     * brief routinely quotes a per-deliverable rate before the package total, and the creator is
     * negotiating the package.
     */
    private static BigDecimal moneyIn(String text) {
        Matcher m = MONEY.matcher(text);
        BigDecimal best = null;
        while (m.find()) {
            String digits = firstNonNull(m.group(1), m.group(3), m.group(5));
            String unit = firstNonNull(m.group(2), m.group(4), m.group(6));
            BigDecimal value = parseAmount(digits, unit);
            if (value == null) {
                continue;
            }
            if (best == null || value.compareTo(best) > 0) {
                best = value;
            }
        }
        return best;
    }

    private static String firstNonNull(String... values) {
        for (String value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static BigDecimal parseAmount(String digits, String unit) {
        if (digits == null) {
            return null;
        }
        String cleaned = digits.replaceAll("[,\\s]", "");
        if (cleaned.isEmpty()) {
            return null;
        }
        BigDecimal value;
        try {
            value = new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
        if (unit != null) {
            String u = unit.toLowerCase(Locale.ROOT);
            if (u.equals("k")) {
                value = value.multiply(BigDecimal.valueOf(1_000));
            } else if (u.startsWith("l")) {
                value = value.multiply(BigDecimal.valueOf(100_000));
            } else if (u.startsWith("cr")) {
                value = value.multiply(BigDecimal.valueOf(10_000_000));
            }
        }
        // A brief that says "5" is not offering five rupees; a figure that low is a count of
        // something. Below this it is noise, and a noise budget is worse than no budget.
        if (value.compareTo(BigDecimal.valueOf(100)) < 0) {
            return null;
        }
        // Round-tripped through toPlainString rather than returned from stripTrailingZeros directly.
        // stripTrailingZeros leaves 8000 as an unscaled 8 with scale -3, which Jackson serialises as
        // "8E+3" -- a creator would read that on the brief card, and it is not equal to
        // new BigDecimal("8000") either, so a test asserting the figure would disagree with a test
        // asserting the JSON. This normalises the scale without changing the value.
        return new BigDecimal(value.stripTrailingZeros().toPlainString());
    }

    private static String isoDateIn(String text) {
        Matcher m = ISO_DATE.matcher(text);
        return m.find() ? m.group(0) : null;
    }

    /** Normalises a duration expressed in weeks or months into days. */
    private static Integer daysIn(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        if (!m.find()) {
            return null;
        }
        String digits = m.group(1) != null ? m.group(1) : m.group(2);
        if (digits == null) {
            return null;
        }
        int value;
        try {
            value = Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return null;
        }
        String matched = m.group(0).toLowerCase(Locale.ROOT);
        if (matched.contains("month")) {
            value = value * 30;
        } else if (matched.contains("week")) {
            value = value * 7;
        }
        return value >= 0 && value <= 3650 ? value : null;
    }

    private static Integer firstInt(Pattern pattern, String text, int min, int max) {
        Matcher m = pattern.matcher(text);
        if (!m.find()) {
            return null;
        }
        for (int group = 1; group <= m.groupCount(); group++) {
            String digits = m.group(group);
            if (digits == null) {
                continue;
            }
            try {
                int value = Integer.parseInt(digits);
                if (value >= min && value <= max) {
                    return value;
                }
            } catch (NumberFormatException ignored) {
                // Next group.
            }
        }
        return null;
    }

    private static String paymentTermsIn(String text) {
        Matcher m = PAYMENT_TERMS.matcher(text);
        if (!m.find()) {
            return null;
        }
        if (m.group(1) != null) {
            return m.group(1) + "% advance";
        }
        return "NET " + m.group(2);
    }

    /**
     * Counts each deliverable type at most once, keeping insertion order so the summary reads in the
     * order the brief mentioned things. A type already matched is not re-matched by a later, broader
     * pattern — which is what stops "1 reel" also counting as a post.
     */
    private static List<DeliverableLine> deliverablesIn(String text) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        String remaining = text;
        for (DeliverablePattern dp : DELIVERABLES) {
            Matcher m = dp.pattern().matcher(remaining);
            if (!m.find()) {
                continue;
            }
            int qty = 1;
            if (m.group(1) != null) {
                try {
                    qty = Math.max(1, Math.min(100, Integer.parseInt(m.group(1).trim())));
                } catch (NumberFormatException ignored) {
                    qty = 1;
                }
            }
            counts.put(dp.type(), qty);
            // Blank out what this pattern consumed so a later, broader pattern cannot re-read it.
            remaining = m.replaceAll(" ");
        }
        List<DeliverableLine> lines = new ArrayList<>();
        counts.forEach((type, qty) -> lines.add(new DeliverableLine(type, qty)));
        return List.copyOf(lines);
    }
}
