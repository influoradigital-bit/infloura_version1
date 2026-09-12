package com.influora.service.risk.rules;

import com.influora.service.risk.RiskContext;
import com.influora.service.risk.RiskSeverity;
import com.influora.service.risk.RiskText;
import com.influora.web.dto.brief.BriefDtos.BriefExtraction;
import com.influora.web.dto.meera.CreatorToolDtos.RiskFlag;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@code REGULATED_CATEGORY} (SPEC.md &sect;5.2) — the sector carries disclosure rules, or the
 * brief asks the creator to repeat a claim she would have to stand behind.
 *
 * <p><b>Not dismissible</b>, per the spec: the obligation does not go away because the creator
 * closed the flag.
 *
 * <p><b>The sub-code gap, stated rather than papered over.</b> The spec lists six regulated
 * categories (FINANCE, HEALTH, RMG, CRYPTO, ALCOHOL, TOBACCO) but only four category sub-codes
 * ({@code SEBI_DISCLOSURE}, {@code ASCI_HEALTH}, {@code RMG_DISCLAIMER}, {@code CRYPTO_DISCLAIMER})
 * plus {@code CLAIMS_SUBSTANTIATION}. ALCOHOL and TOBACCO therefore have no sub-code of their own.
 * They still fire — surfacing surrogate-advertising exposure matters more than a tidy map — and
 * carry {@code CLAIMS_SUBSTANTIATION} when the brief also makes claims, or no {@code sub_code} at
 * all when it does not. Inventing an unlisted code would put a string the frontend has no branch
 * for into a contract field.
 */
public final class RegulatedCategoryRule implements RiskRule {

    public static final String CODE = "REGULATED_CATEGORY";
    public static final String TITLE = "Regulated category, extra rules apply";

    public static final String SUB_CODE_CLAIMS = "CLAIMS_SUBSTANTIATION";

    /** SPEC.md &sect;5.2 / &sect;2.11 — the six values {@code regulated_category} may take. */
    private static final Set<String> REGULATED =
            Set.of("FINANCE", "HEALTH", "RMG", "CRYPTO", "ALCOHOL", "TOBACCO");

    /** The four category sub-codes the spec names. ALCOHOL/TOBACCO are absent by design — see javadoc. */
    private static final Map<String, String> SUB_CODES =
            Map.of(
                    "FINANCE", "SEBI_DISCLOSURE",
                    "HEALTH", "ASCI_HEALTH",
                    "RMG", "RMG_DISCLAIMER",
                    "CRYPTO", "CRYPTO_DISCLAIMER");

    @Override
    public Optional<RiskFlag> apply(RiskContext ctx) {
        BriefExtraction extraction = ctx.extraction();
        if (extraction == null) {
            return Optional.empty();
        }
        String category =
                RiskText.blank(extraction.regulatedCategory())
                        ? null
                        : extraction.regulatedCategory().trim().toUpperCase(Locale.ROOT);
        boolean regulated = category != null && REGULATED.contains(category);
        List<String> claims = extraction.claims();
        boolean hasClaims = claims != null && claims.stream().anyMatch(c -> !RiskText.blank(c));

        if (!regulated && !hasClaims) {
            return Optional.empty();
        }

        String subCode = regulated ? SUB_CODES.get(category) : null;
        if (subCode == null && hasClaims) {
            subCode = SUB_CODE_CLAIMS;
        }

        String detail =
                regulated
                        ? category + " promotions carry disclosure rules you have to follow on the post."
                        : "The brief makes claims you would be repeating in your own voice.";

        return Optional.of(
                new RiskFlag(
                        CODE,
                        RiskSeverity.WARN.name(),
                        TITLE,
                        detail,
                        null,
                        "Ask the brand for the evidence in the deal thread before you script anything.",
                        RiskText.data(
                                "regulated_category", category,
                                "sub_code", subCode,
                                "claim_count", hasClaims ? String.valueOf(countClaims(claims)) : null),
                        false));
    }

    private static int countClaims(List<String> claims) {
        return (int) claims.stream().filter(c -> !RiskText.blank(c)).count();
    }
}
