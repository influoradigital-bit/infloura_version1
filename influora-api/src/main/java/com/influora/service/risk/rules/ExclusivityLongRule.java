package com.influora.service.risk.rules;

import com.influora.common.Rendered;
import com.influora.domain.enums.ExclusivityScope;
import com.influora.service.risk.RiskContext;
import com.influora.service.risk.RiskSeverity;
import com.influora.service.risk.RiskText;
import com.influora.web.dto.brief.BriefDtos.BriefExtraction;
import com.influora.web.dto.creator.CreatorAgentDtos.PreferencesResponse;
import com.influora.web.dto.meera.CreatorToolDtos.RiskFlag;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Optional;

/**
 * {@code EXCLUSIVITY_LONG} (SPEC.md &sect;5.2) — the creator is being asked to lock a category or a
 * brand set for a long stretch, and that lock has a price.
 *
 * <p><b>Severity.</b> The spec gives "INFO at 30 days; WARN at &ge; 60 days or CATEGORY scope with
 * &ge; 1 deal in that category in 90 days", and a trigger of "{@code exclusivity_days} set". Both
 * are honoured literally: any positive {@code exclusivity_days} fires, INFO below 60 days (so 30
 * days is INFO, as the spec says), WARN at 60 or more, and WARN at any length when the scope is
 * CATEGORY and the creator has actually worked in that category recently — a 30-day category lock
 * costs a creator with two beauty deals a quarter far more than it costs one without.
 *
 * <p><b>The cost figure.</b> {@code lost income = reel floor} &times; {@code days / 30}, rounded to
 * whole rupees. It is an estimate and reads as one (the {@code cost} field is prefixed with
 * U+2248). The reel floor is the stand-in for "your usual rate" because it is the only per-piece
 * number the creator has actually set; when she has none, the flag still fires but says nothing
 * about money rather than inventing a figure.
 */
public final class ExclusivityLongRule implements RiskRule {

    public static final String CODE = "EXCLUSIVITY_LONG";
    public static final String TITLE = "Long exclusivity window";

    /** SPEC.md &sect;5.2 — at or above this many days the flag is a WARN regardless of scope. */
    private static final int WARN_FROM_DAYS = 60;

    /** SPEC.md &sect;5.2 — the look-back for "&ge; 1 deal in that category". */
    private static final int RECENT_CATEGORY_DAYS = 90;

    /** Days per month for the lost-income estimate. A month is 30 days here, deliberately. */
    private static final BigDecimal DAYS_PER_MONTH = new BigDecimal("30");

    @Override
    public Optional<RiskFlag> apply(RiskContext ctx) {
        BriefExtraction extraction = ctx.extraction();
        Integer days = extraction == null ? null : extraction.exclusivityDays();
        if (days == null || days <= 0) {
            return Optional.empty();
        }

        boolean categoryScope = ExclusivityScope.CATEGORY.name().equalsIgnoreCase(extraction.exclusivityScope());
        boolean worksInCategory = categoryScope && hasRecentDealInCategory(ctx, extraction.category());
        RiskSeverity severity =
                days >= WARN_FROM_DAYS || worksInCategory ? RiskSeverity.WARN : RiskSeverity.INFO;

        BigDecimal lostIncome = lostIncome(ctx.prefs(), days);
        String renderedLoss = lostIncome == null ? null : Rendered.money(lostIncome, ctx.locale());

        String detail =
                renderedLoss == null
                        ? days + " days exclusivity with no other work in this category."
                        : days + " days exclusivity " + RiskText.APPROX + " " + renderedLoss + " at your usual rate.";

        return Optional.of(
                new RiskFlag(
                        CODE,
                        severity.name(),
                        TITLE,
                        detail,
                        renderedLoss == null ? null : RiskText.APPROX + " " + renderedLoss + " of lost income",
                        "Price the exclusivity add-on before you agree.",
                        RiskText.data(
                                "exclusivity_days", String.valueOf(days),
                                "exclusivity_scope",
                                        RiskText.blank(extraction.exclusivityScope())
                                                ? null
                                                : extraction.exclusivityScope().trim().toUpperCase(Locale.ROOT),
                                "lost_income", renderedLoss,
                                "recent_deals_in_category", worksInCategory ? "true" : "false"),
                        true));
    }

    /** SPEC.md &sect;5.2 — at least one of the creator's own deals in the same category in 90 days. */
    private static boolean hasRecentDealInCategory(RiskContext ctx, String category) {
        if (RiskText.blank(category)) {
            return false;
        }
        String target = RiskText.norm(category);
        return ctx.activeDeals().stream()
                .filter(deal -> !RiskText.blank(deal.category()))
                .filter(deal -> RiskText.norm(deal.category()).equals(target))
                .anyMatch(
                        deal ->
                                deal.appliedAt() != null
                                        && deal.appliedAt()
                                                .isAfter(ctx.now().minus(RECENT_CATEGORY_DAYS, ChronoUnit.DAYS)));
    }

    /** @return null when the creator has no usable rate to estimate against */
    private static BigDecimal lostIncome(PreferencesResponse prefs, int days) {
        BigDecimal rate = prefs == null ? null : prefs.reelFloor();
        if (rate == null || rate.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return rate.multiply(BigDecimal.valueOf(days))
                .divide(DAYS_PER_MONTH, 0, RoundingMode.HALF_UP);
    }
}
