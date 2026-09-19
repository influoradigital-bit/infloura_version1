package com.influora.service.risk.rules;

import com.influora.service.risk.RiskContext;
import com.influora.service.risk.RiskSeverity;
import com.influora.service.risk.RiskText;
import com.influora.web.dto.creator.CreatorAgentDtos.PreferencesResponse;
import com.influora.web.dto.meera.CreatorToolDtos.RiskFlag;
import java.util.Optional;

/**
 * {@code EXCLUDED_CATEGORY} (SPEC.md &sect;5.2) — the deal is in a category this creator asked
 * never to be shown.
 *
 * <p>One comparison, not two. The spec phrases the trigger as "{@code extraction.category} or
 * {@code endBrandCategory}", but {@code DealRiskService.evaluateDeal} already folds the campaign's
 * {@code endBrandCategory} INTO {@code extraction.category} when it builds the deal's view — that
 * is the whole point of the extraction-shaped view — so checking both here would be checking the
 * same field twice under two names.
 */
public final class ExcludedCategoryRule implements RiskRule {

    public static final String CODE = "EXCLUDED_CATEGORY";
    public static final String TITLE = "Category you asked to avoid";

    @Override
    public Optional<RiskFlag> apply(RiskContext ctx) {
        PreferencesResponse prefs = ctx.prefs();
        String category = ctx.extraction() == null ? null : ctx.extraction().category();
        if (prefs == null || RiskText.blank(category)) {
            return Optional.empty();
        }
        if (!RiskText.containsNormalised(prefs.excludedCategories(), category)) {
            return Optional.empty();
        }

        String display = category.trim();
        return Optional.of(
                new RiskFlag(
                        CODE,
                        RiskSeverity.CRITICAL.name(),
                        TITLE,
                        display + " is on the list of categories you asked to stay out of.",
                        null,
                        "Send a short, polite decline.",
                        RiskText.data("category", display, "recommended_move", "DECLINE"),
                        true));
    }
}
