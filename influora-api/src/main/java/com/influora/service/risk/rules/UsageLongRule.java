package com.influora.service.risk.rules;

import com.influora.service.risk.RiskContext;
import com.influora.service.risk.RiskSeverity;
import com.influora.service.risk.RiskText;
import com.influora.web.dto.brief.BriefDtos.BriefExtraction;
import com.influora.web.dto.meera.CreatorToolDtos.RiskFlag;
import java.util.Optional;

/**
 * {@code USAGE_LONG} (SPEC.md &sect;5.2) — the brand keeps usage rights for a stated number of
 * months.
 *
 * <p><b>The 7-to-12 month gap.</b> The spec gives two severities — "INFO &le; 6 months, WARN &gt;
 * 12 months" — and says nothing about the eleven values in between. Resolved here as INFO: WARN is
 * defined by a threshold the deal has not crossed, and quietly promoting a 9-month window to WARN
 * would make this rule shout on the most ordinary usage term in the market.
 *
 * <p>Silent when {@code usage_perpetual} is set: {@code USAGE_PERPETUAL} already carries that deal,
 * and two flags about the same clause read as two problems.
 */
public final class UsageLongRule implements RiskRule {

    public static final String CODE = "USAGE_LONG";
    public static final String TITLE = "Long usage window";

    /** SPEC.md &sect;5.2 — above this many months the flag is a WARN. */
    private static final int WARN_ABOVE_MONTHS = 12;

    @Override
    public Optional<RiskFlag> apply(RiskContext ctx) {
        BriefExtraction extraction = ctx.extraction();
        if (extraction == null || extraction.usagePerpetual()) {
            return Optional.empty();
        }
        Integer months = extraction.usageMonths();
        if (months == null || months <= 0) {
            return Optional.empty();
        }

        RiskSeverity severity = months > WARN_ABOVE_MONTHS ? RiskSeverity.WARN : RiskSeverity.INFO;
        return Optional.of(
                new RiskFlag(
                        CODE,
                        severity.name(),
                        TITLE,
                        "Usage window " + months + " months.",
                        null,
                        "Price the repost or paid-ads add-on for the months beyond the campaign.",
                        RiskText.data("usage_months", String.valueOf(months)),
                        true));
    }
}
