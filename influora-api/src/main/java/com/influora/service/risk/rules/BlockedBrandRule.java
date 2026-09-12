package com.influora.service.risk.rules;

import com.influora.service.risk.RiskContext;
import com.influora.service.risk.RiskSeverity;
import com.influora.service.risk.RiskText;
import com.influora.web.dto.creator.CreatorAgentDtos.PreferencesResponse;
import com.influora.web.dto.meera.CreatorToolDtos.RiskFlag;
import java.util.Optional;

/**
 * {@code BLOCKED_BRAND} (SPEC.md &sect;5.2) — the brand is on the creator's own block list.
 *
 * <p>Case-insensitive and trimmed, per the spec, via {@link RiskText#containsNormalised}. It is
 * deliberately NOT a substring match: a creator who blocks "Glow" must not silently decline
 * "Glowworm Books", and a block list is exactly the setting where a false positive costs the
 * creator a deal she wanted with no visible reason.
 *
 * <p>Prefers {@link RiskContext#brandName()} (resolved from the campaign's end-brand or the brand
 * workspace on the deal path) over the extractor's guess, and falls back to the guess for a pasted
 * brief that has no deal behind it.
 */
public final class BlockedBrandRule implements RiskRule {

    public static final String CODE = "BLOCKED_BRAND";
    public static final String TITLE = "Brand you asked to block";

    @Override
    public Optional<RiskFlag> apply(RiskContext ctx) {
        PreferencesResponse prefs = ctx.prefs();
        String brand =
                !RiskText.blank(ctx.brandName())
                        ? ctx.brandName()
                        : ctx.extraction() == null ? null : ctx.extraction().brandName();
        if (prefs == null || RiskText.blank(brand)) {
            return Optional.empty();
        }
        if (!RiskText.containsNormalised(prefs.blockedBrands(), brand)) {
            return Optional.empty();
        }

        String display = brand.trim();
        return Optional.of(
                new RiskFlag(
                        CODE,
                        RiskSeverity.CRITICAL.name(),
                        TITLE,
                        display + " is on your blocked-brands list.",
                        null,
                        "Send a decline without naming the block list.",
                        RiskText.data("brand_name", display, "recommended_move", "DECLINE"),
                        true));
    }
}
