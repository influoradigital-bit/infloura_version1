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
 * <p>The regex is the spec's, matched against {@link RiskText#norm}'s output — which is why it
 * catches {@code don't} written with a curly apostrophe, the form every brand pasting from a
 * document actually sends.
 */
public final class HideDisclosureRule implements RiskRule {

    public static final String CODE = "HIDE_DISCLOSURE";
    public static final String TITLE = "Brand asked you to hide the ad label";

    /** SPEC.md &sect;5.2, verbatim: {@code (no|don'?t|without)\s+(#ad|#collab|#sponsored|disclos|paid partnership)}. */
    private static final Pattern HIDE_TEXT =
            Pattern.compile(
                    "(no|don'?t|without)\\s+(#ad|#collab|#sponsored|disclos|paid partnership)",
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
