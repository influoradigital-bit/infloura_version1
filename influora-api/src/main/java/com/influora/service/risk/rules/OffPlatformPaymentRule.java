package com.influora.service.risk.rules;

import com.influora.service.risk.RiskContext;
import com.influora.service.risk.RiskSeverity;
import com.influora.service.risk.RiskText;
import com.influora.web.dto.brief.BriefDtos.BriefExtraction;
import com.influora.web.dto.meera.CreatorToolDtos.RiskFlag;
import java.util.Optional;
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
     * SPEC.md &sect;5.2, verbatim: {@code \b(upi|gpay|phonepe|paytm|bank transfer|neft|imps|pay(ment)?
     * after)\b}.
     *
     * <p>The word boundaries matter more here than anywhere else in the table: without them
     * {@code imps} matches "impressions" and every analytics discussion becomes a payment-fraud
     * signal.
     */
    private static final Pattern OFF_PLATFORM_TEXT =
            Pattern.compile(
                    "\\b(upi|gpay|phonepe|paytm|bank transfer|neft|imps|pay(ment)? after)\\b",
                    Pattern.CASE_INSENSITIVE);

    @Override
    public Optional<RiskFlag> apply(RiskContext ctx) {
        BriefExtraction extraction = ctx.extraction();
        boolean hinted = extraction != null && extraction.offPlatformPaymentHint();
        boolean inText = RiskText.matches(ctx.text(), OFF_PLATFORM_TEXT);
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
