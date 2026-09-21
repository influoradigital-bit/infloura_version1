package com.influora.service.risk.rules;

import com.influora.service.risk.RiskContext;
import com.influora.service.risk.RiskSeverity;
import com.influora.service.risk.RiskText;
import com.influora.web.dto.brief.BriefDtos.BriefExtraction;
import com.influora.web.dto.meera.CreatorToolDtos.RiskFlag;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code OFF_PLATFORM_PAYMENT} (SPEC.md &sect;5.2) — the brand is steering payment outside the
 * platform.
 *
 * <p><b>Shadow mode.</b> This flag is raised and logged; it NEVER blocks anything. Nothing in this
 * class or in {@code DealRiskService} refuses a draft, a send, or a status change because of it —
 * the creator sees the flag, keeps the report button, and decides. The audit row that pairs with
 * it is written by {@code DealRiskService}, not here (a rule is a pure function), and carries the
 * brand's workspace id and nothing else: never the creator's name, never the matched text.
 *
 * <p><b>Not dismissible</b>, per the spec, alongside {@code HIDE_DISCLOSURE} and
 * {@code REGULATED_CATEGORY}. Shadow mode and non-dismissible are not in tension: the flag cannot
 * stop the deal, and the creator cannot make it disappear from the record either.
 *
 * <p>Vocabulary: "Secure Payments" and "secured funds" — the platform word for this is banned in
 * creator-facing copy.
 */
public final class OffPlatformPaymentRule implements RiskRule {

    public static final String CODE = "OFF_PLATFORM_PAYMENT";
    public static final String TITLE = "Payment offered outside the platform";

    /**
     * K-2b round 5, Ruling 1 (Priya, {@code RULINGS-U-0917.md} round 5): a payment method's name
     * alone is Influora's own payout vocabulary ({@code creator-wallet.tsx} L1068, L1131 —
     * "Add a UPI ID or bank account to withdraw funds"), so a bare match is a structural
     * false-positive source, not corpus noise (measured false positives on {@code OPP-N-02},
     * {@code OPP-N-09} and Kabir's own {@code KAB-OP-N-03}, every one an on-platform payout
     * instruction). It now counts only together with a request to send or pay money TO the
     * creator: the four blind true positives this must not lose all read that way ("pay you
     * directly via UPI", "UPI ID bhej do", "UPI \u0928\u0902\u092C\u0930 \u092D\u0947\u091C
     * \u0926\u0940\u091C\u093F\u090F", "upi pe direct bhej denge").
     *
     * <p><b>Constraint A (Priya): positive context only, never an exclusion list.</b> Firing must
     * not depend on the ABSENCE of words like "Influora", "payout", "wallet" or "withdraw" — an
     * exclusion lets brand text switch the check off ("send it to my UPI instead of the Influora
     * payout"). {@link #WALLET_NAME} plus {@link #SEND_REQUEST}, both required, is the positive
     * signal; nothing here reads for "Influora" and nothing should ever be added that does.
     * {@code RiskFlagCorpusTest.offPlatformPaymentSuppressionAttemptStillFires} falsifies exactly
     * this: Influora-safe wording appended to a caught row must not suppress it.
     *
     * <p><b>Constraint B: the route phrases need no wallet name.</b> {@link #ROUTE_PHRASES} is
     * unchanged from round 4 and fires on its own — "pay you directly", "outside/off the
     * platform", "direct payment/transfer", "platform ke bahar" and the Devanagari form.
     *
     * <p>See {@link #matches} for how the two groups combine. {@link #WALLET_NAME}'s word boundary
     * matters more than anywhere else in the table: without it {@code imps} matches "impressions"
     * and every analytics discussion becomes a payment-fraud signal. Deliberately NOT added to
     * {@link #SEND_REQUEST}: bare {@code cash}, which Kabir judged too noisy on its own and left to
     * the model's hint (round 4).
     *
     * <p><b>KB5-1 ({@code KABIR-CONSENT-0917.md} "Last call — K-2b round 5"): the pairing is by
     * proximity, not by co-occurrence anywhere in the brief.</b> Round 5 Ruling 1 says the wallet
     * name and the send/pay word must appear "alongside" each other; as first built, {@link
     * #matches} accepted the two words anywhere in up to 8,000 characters, which re-admitted the
     * payout-instruction false positive Ruling 1 exists to remove, through an unrelated "send" in
     * a different sentence — measured non-dismissible on two ordinary on-platform briefs: "Please
     * send the draft for approval by Friday. Your fee is released to the UPI ID saved in your
     * Influora payout settings." and "Send us the raw files on Drive. Make sure your bank account
     * or UPI is added in Influora for withdrawal." {@link #matches} now requires the request word
     * within {@link #PAIRING_WINDOW} tokens of the wallet name. Measured on Nisha's OFF_PLATFORM_PAYMENT
     * rows, the corpus test's non-blind rows and Kabir's probes: false flags 4 &rarr; 2, no miss
     * added, still linear.
     *
     * <p><b>Two residual false flags, left as-is, on purpose.</b> "Influora will pay you via UPI
     * once the reel is approved." and "You'll get paid to the UPI ID in your Influora wallet after
     * approval." still flag: only the sentence's subject (Influora, not the brand) differs from a
     * real off-platform ask, and Constraint A forbids keying on "Influora" to tell the two apart.
     * They are deliberately NOT added as {@code NO_FLAG} corpus rows — the only way to pass them
     * would be an Influora exclusion, which Priya has twice forbidden. They are left to the live
     * 50-brief sample's {@code basis} split (round 4 &sect;2 item 5); if they dominate the false
     * flags there, that is the evidence for removing the regex half (option (c)), not a reason to
     * add an exclusion here.
     *
     * <p><b>Devanagari and the nukta escape.</b> {@link #ROUTE_PHRASES}' Devanagari alternative is
     * bounded with letter-and-mark lookarounds, not {@code \b} — see {@code
     * HideDisclosureRule.HIDE_TEXT}'s javadoc for why a plain word boundary never fires next to
     * Devanagari text under this build's default regex flags — and is written as explicit Unicode
     * escapes because the nukta letter fa's precomposed code point, U+095E, is verified on this JDK
     * to NOT survive {@link java.text.Normalizer} NFC, which always yields the decomposed pair
     * U+092B (pha) + U+093C (combining nukta) instead; {@link RiskText#norm} NFC-normalises the
     * text it matches against, so a pattern literal written with U+095E would never match. {@link
     * #SEND_REQUEST}'s Devanagari alternative (the "send" root {@code \u092D\u0947\u091C}, "bhej")
     * uses the same lookaround for the same reason, and is written as a bare root deliberately: the
     * lookaround only guards the LEFT edge, so it still matches as a prefix of any conjugated form
     * ({@code \u092D\u0947\u091C\u093F\u090F}, "bhejiye"; {@code \u092D\u0947\u091C\u094B}, "bhejo")
     * without needing {@code \w*}, which under this build's default flags only ever matches ASCII
     * word characters and would match zero Devanagari letters after the root anyway.
     */
    static final Pattern WALLET_NAME =
            Pattern.compile(
                    "\\b(?:upi|gpay|phonepe|paytm|bank\\s+transfer|neft|imps|rtgs|google\\s*pay)\\b",
                    Pattern.CASE_INSENSITIVE);

    /** K-2b round 5: a request to move money to the creator, paired with {@link #WALLET_NAME}. */
    static final Pattern SEND_REQUEST =
            Pattern.compile(
                    "\\b(?:send|sends|sending|sent|pay|pays|paying|paid|transfer|transfers|transferring|transferred|bhej\\w*)\\b"
                            + "|(?<![\\p{L}\\p{M}])\u092D\u0947\u091C",
                    Pattern.CASE_INSENSITIVE);

    /** K-2b round 4 (unchanged by round 5, Constraint B): fires on its own, no wallet name needed. */
    static final Pattern ROUTE_PHRASES =
            Pattern.compile(
                    "\\b(?:outside|off)\\s+(?:the\\s+)?(?:platform|influora)\\b"
                            + "|\\bpay\\s+you\\s+directly\\b"
                            + "|\\bdirect\\s+(?:payment|transfer)\\b"
                            + "|\\bplatform\\s+ke\\s+bahar\\b"
                            + "|(?<![\\p{L}\\p{M}])\u092A\u094D\u0932\u0947\u091F\u092B\u093C\u0949\u0930\u094D\u092E"
                            + "\\s+\u0915\u0947\\s+\u092C\u093E\u0939\u0930(?![\\p{L}\\p{M}])",
                    Pattern.CASE_INSENSITIVE);

    /**
     * KB5-1 ({@code KABIR-CONSENT-0917.md} "Last call — K-2b round 5"): how many tokens apart the
     * wallet name and the send/pay word may be and still count as "alongside" (Round 5 Ruling 1).
     * Measured on the corpus: window 6 drops the built rule's false flags from 4 to 2 with no miss
     * added, and stays linear.
     */
    static final int PAIRING_WINDOW = 6;

    /**
     * The token that {@link #WALLET_NAME} matches are replaced with before splitting on
     * whitespace, so a multi-word wallet phrase ("bank transfer", "google pay") collapses to one
     * token position rather than two. Deliberately a Private Use Area code point (U+E000): above
     * U+0020 so {@link String#trim()} never deletes it (Kabir's first attempt used U+0001, a
     * control character, and lost {@code OPP-F-11} and "PhonePe number bhejo…" whenever the wallet
     * name opened the text, because {@code trim()} silently ate a leading occurrence), not a
     * Unicode space or {@code \s} character so {@code split("\\s+")} keeps it as its own token
     * instead of swallowing it, and not a code point {@link RiskText#norm} or any brief text can
     * produce, so it can never collide with real input.
     *
     * <p>Built as {@code String.valueOf((char) 0xE000)} rather than a {@code ""} literal or
     * a raw embedded character: U+E000 has no assigned glyph, so either literal form renders as
     * empty-looking quotes in a diff, an editor or a plain read of the file — indistinguishable
     * from an actually-empty string, which is exactly the false "this constant is unfinished"
     * read a reviewer got from this file before this comment existed.
     */
    private static final String WALLET_TOKEN = String.valueOf((char) 0xE000);

    /**
     * F-0769 / K-2c (Priya, {@code RULINGS-U-0917.md} round 6, Ruling 1). A run of sentence-ending
     * punctuation, followed by whitespace or end of text: {@code . ! ? …} (U+2026, horizontal
     * ellipsis), {@code ।} (U+0964, danda) and {@code ॥} (U+0965, double danda). See {@link
     * #isProtectedDot} for the one exception, which applies only to a run that is exactly one
     * {@code .}.
     *
     * <p><b>F-0776 / K-2c.2 (round 7, Ruling 4): a pipe added.</b> A brand or agency sometimes
     * types an ASCII pipe in place of a danda (no Devanagari keyboard, or a chat-app autocorrect
     * artifact), and the un-cut pipe let an unrelated wallet name and request word either side of
     * it pair up -- measured non-dismissible on three ordinary on-platform briefs, English,
     * Hinglish and Devanagari alike ("Send the draft by Monday, pipe, UPI payouts go through
     * Influora as usual", "Draft bhej do kal tak, pipe, UPI se payout Influora pe aayega", the same
     * shape in Devanagari). Adding the pipe can only add cuts, so it can only remove flags (the
     * same argument as round 6's sentence cut); a real ask written across a pipe still flags, the
     * wallet name and request word still within the pairing window either side of the cut, the same
     * way a hard line-wrap still flags. A comma still does not cut, so a pipe-separated rate list
     * followed by a real ask in its last segment still flags there -- that is {@link
     * #PAIRING_WINDOW}, not this terminator set.
     */
    static final Pattern SENTENCE_TERMINATOR = Pattern.compile("[.!?\u2026\u0964\u0965|]+(?=\\s|$)");

    /** F-0769: a blank line — two line breaks with only spaces/tabs between them — or U+2029 (paragraph separator). */
    static final Pattern SENTENCE_BLANK_LINE = Pattern.compile("\\r?\\n[ \\t]*\\r?\\n|\u2029");

    /**
     * F-0769: a line break followed by optional spaces/tabs, then a list marker, then a space. The
     * markers are {@code - * • · ▪ ➤}, or one or two digits followed by {@code .} or {@code )}. A
     * line break on its own (not before a list marker, and not part of a blank line) is deliberately
     * NOT a cut — see {@link #sentences}'s class-level note on hard-wrapped text.
     */
    static final Pattern SENTENCE_LIST_ITEM_LINE =
            Pattern.compile("\\r?\\n(?=[ \\t]*(?:[-*\u2022\u00b7\u25aa\u27a4]|\\p{Nd}{1,2}[.)])\\s)");

    /**
     * F-0769: words that may sit before a single {@code .} without ending the sentence (see {@link
     * #isProtectedDot}) — {@code Rs. 5,000}, {@code No. 12}, {@code approx. 3 days}, {@code a/c
     * no.}, {@code amt.}, {@code e.g.} and the Devanagari {@code रु.} (rupee).
     *
     * <p><b>How this list may grow.</b> A word belongs here only if it almost never ends a sentence
     * and commonly sits inside a payment ask, and adding one needs a row showing a real ask lost
     * without it. {@code etc}, {@code ltd} and {@code co} are deliberately NOT on this list: they
     * often do end a sentence, and with {@code etc} off, "Send the draft, captions etc. UPI payout
     * via Influora as usual." is correctly cut into two sentences (measured, {@code
     * F0769Probe.java}).
     */
    static final Set<String> SENTENCE_DOT_ABBREVIATIONS =
            Set.of(
                    "rs", "re", "inr", "amt", "approx", "appx", "no", "nos", "a/c", "acc", "acct",
                    "e.g", "i.e", "vs", "mr", "mrs", "ms", "dr", "pvt",
                    "\u0930\u0941" /* रु, "ru" (rupee) */);

    /**
     * K-2b round 5: the rule's text path, exposed so {@code RiskFlagCorpusTest} runs the corpus
     * against exactly what {@link #apply} runs against, not against a single Pattern constant
     * (Ruling 1 needs two patterns ANDed together, which {@link RiskText#matches}'s one-pattern
     * shape cannot express).
     *
     * <p>KB5-1: the wallet-name match and the send/pay-word match must be within {@link
     * #PAIRING_WINDOW} tokens of each other, not merely both present anywhere in the text.
     *
     * <p><b>F-0769 / K-2c (round 6, Ruling 1): the pairing only counts inside one sentence.</b> The
     * un-windowed proximity check above still counted straight through a sentence end, so short,
     * ordinary on-platform briefs such as "Send the draft by Monday. UPI payouts go through
     * Influora as usual." still paired a wallet name in one sentence with an unrelated "send" in
     * the next and raised a non-dismissible flag against an honest brand. {@link #sentences} cuts
     * the normalised text into sentences first ({@link #SENTENCE_TERMINATOR}, {@link
     * #SENTENCE_BLANK_LINE}, {@link #SENTENCE_LIST_ITEM_LINE}, with the {@link #isProtectedDot}
     * exception), and {@link #pairsWithinSentence} — the token-window check this method used to run
     * once over the whole text — now runs once per sentence. {@link #ROUTE_PHRASES} is unaffected
     * (Constraint B): it still runs on the whole normalised text before any sentence splitting.
     *
     * <p><b>This can only remove flags, never add one</b> (measured on 72 rows, {@code
     * F0769Probe.java}): every candidate boundary rule only narrows which token pairs may be
     * compared, so a text the un-split rule does not flag is never flagged after splitting.
     *
     * <p><b>The price.</b> A real off-platform ask written as two sentences — "Share your UPI.
     * We'll send it tonight." — stops flagging, in English, Hinglish (a {@code ?} form) and
     * Devanagari alike. That is rewriting the ask as a second sentence, the same class of evasion
     * as putting a seventh word in between, which {@link #PAIRING_WINDOW} already concedes; the
     * regex half is a tripwire, not a control against a motivated brand (round 5 §2a). These rows
     * are corpus {@code SHOULD_FLAG} rows, reported but deliberately not in the ratchet.
     *
     * <p><b>The no-space residual, left on purpose.</b> "…kal tak.UPI se payout…", with no space
     * after the full stop, still flags: {@link #SENTENCE_TERMINATOR} requires whitespace or end of
     * text after the punctuation run, so a dot glued directly to the next word is not a cut.
     * Cutting at a bare {@code .} followed by a letter would also split URLs, e-mail addresses and
     * abbreviations like "e.g.", and needs its own measurement; it is left to the live 50-brief
     * sample's {@code basis} split, by design, the same as the two Influora-subject residuals above.
     */
    static boolean matches(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String norm = RiskText.norm(text);
        if (ROUTE_PHRASES.matcher(norm).find()) {
            return true;
        }
        if (!WALLET_NAME.matcher(norm).find()) {
            return false;
        }
        for (String sentence : sentences(norm)) {
            if (pairsWithinSentence(sentence)) {
                return true;
            }
        }
        return false;
    }

    /**
     * F-0769: splits an already-{@link RiskText#norm}-normalised text into sentences, so {@link
     * #matches} can pair a wallet name and a request word only within one of them. A line break on
     * its own is deliberately not a cut — plain-text and PDF pastes routinely hard-wrap a real ask
     * mid-sentence ("…we can send the fee straight to your\nUPI, faster that way…"), and cutting at
     * every line break lost both hard-wrapped real asks measured against this corpus. Only a blank
     * line or a line break that starts a list item counts.
     */
    static List<String> sentences(String norm) {
        TreeSet<Integer> cuts = new TreeSet<>();
        Matcher terminator = SENTENCE_TERMINATOR.matcher(norm);
        while (terminator.find()) {
            if (!isProtectedDot(norm, terminator.start(), terminator.end())) {
                cuts.add(terminator.end());
            }
        }
        Matcher blankLine = SENTENCE_BLANK_LINE.matcher(norm);
        while (blankLine.find()) {
            cuts.add(blankLine.start());
        }
        Matcher listItem = SENTENCE_LIST_ITEM_LINE.matcher(norm);
        while (listItem.find()) {
            cuts.add(listItem.start());
        }
        List<String> sentences = new ArrayList<>();
        int previous = 0;
        for (int cut : cuts) {
            sentences.add(norm.substring(previous, cut));
            previous = cut;
        }
        sentences.add(norm.substring(previous));
        return sentences;
    }

    /**
     * F-0769: true when the terminator run at {@code [start, end)} is a single {@code .} that does
     * NOT end the sentence, because either the next non-space character on the same line is a digit
     * ({@code \p{Nd}}, which includes Devanagari digits) or a currency symbol ({@code \p{Sc}},
     * which includes ₹) — "Rs. 5,000", "No. 12", "approx. 3 days", "रु. 5000" — or the word
     * immediately before the dot, with leading open brackets/quotes stripped, is on {@link
     * #SENTENCE_DOT_ABBREVIATIONS}. Every other terminator (a run longer than one character, or a
     * lone {@code !}, {@code ?}, {@code …}, {@code ।} or {@code ॥}) always ends the sentence.
     */
    private static boolean isProtectedDot(String norm, int start, int end) {
        if (end - start != 1 || norm.charAt(start) != '.') {
            return false;
        }
        int i = end;
        while (i < norm.length() && Character.isWhitespace(norm.charAt(i)) && norm.charAt(i) != '\n' && norm.charAt(i) != '\r') {
            i++;
        }
        if (i < norm.length()) {
            int type = Character.getType(norm.codePointAt(i));
            if (type == Character.DECIMAL_DIGIT_NUMBER || type == Character.CURRENCY_SYMBOL) {
                return true;
            }
        }
        int wordStart = start;
        while (wordStart > 0 && !Character.isWhitespace(norm.charAt(wordStart - 1))) {
            wordStart--;
        }
        String word = norm.substring(wordStart, start).replaceAll("^[\\p{Ps}\\p{Pi}\"'~]+", "");
        return SENTENCE_DOT_ABBREVIATIONS.contains(word);
    }

    /**
     * The token-window pairing check {@link #matches} used to run once over the whole normalised
     * text (K-2b round 5, KB5-1); F-0769 now runs it once per sentence from {@link #sentences}.
     * Every {@link #WALLET_NAME} match is first replaced by a single {@link #WALLET_TOKEN}, so a
     * multi-word wallet phrase occupies one token position; the sentence is then split on
     * whitespace and each remaining token is tested against {@link #SEND_REQUEST} on its own —
     * which keeps intact the reason {@link #WALLET_NAME} matches are stripped before that check at
     * all: "google pay" is itself a wallet name whose own text contains the word "pay", so testing
     * an un-stripped string would let a bare "Google Pay" satisfy its own send/pay-request
     * requirement (caught by {@code RiskFlagCorpusTest.bareWalletNameAloneStaysSilent} before this
     * shipped).
     */
    private static boolean pairsWithinSentence(String sentence) {
        if (!WALLET_NAME.matcher(sentence).find()) {
            return false;
        }
        String[] tokens = WALLET_NAME.matcher(sentence).replaceAll(" " + WALLET_TOKEN + " ").trim().split("\\s+");
        List<Integer> wallets = new ArrayList<>();
        List<Integer> requests = new ArrayList<>();
        for (int i = 0; i < tokens.length; i++) {
            if (tokens[i].equals(WALLET_TOKEN)) {
                wallets.add(i);
            } else if (SEND_REQUEST.matcher(tokens[i]).find()) {
                requests.add(i);
            }
        }
        for (int w : wallets) {
            for (int r : requests) {
                if (Math.abs(w - r) <= PAIRING_WINDOW) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public Optional<RiskFlag> apply(RiskContext ctx) {
        BriefExtraction extraction = ctx.extraction();
        boolean hinted = extraction != null && extraction.offPlatformPaymentHint();
        boolean inText = matches(ctx.text());
        if (!hinted && !inText) {
            return Optional.empty();
        }

        return Optional.of(
                new RiskFlag(
                        CODE,
                        RiskSeverity.WARN.name(),
                        TITLE,
                        "Paying outside Secure Payments loses dispute cover.",
                        null,
                        "Reply steering the brand back to Secure Payments so the funds are secured before you start.",
                        RiskText.data(
                                "basis", hinted ? "STATED" : "BRIEF_TEXT",
                                "mode", "SHADOW",
                                "blocks", "false"),
                        false));
    }
}
