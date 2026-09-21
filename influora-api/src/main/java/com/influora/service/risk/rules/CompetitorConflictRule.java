package com.influora.service.risk.rules;

import com.influora.common.Rendered;
import com.influora.domain.enums.CollaborationStatus;
import com.influora.domain.enums.ExclusivityScope;
import com.influora.service.risk.RiskContext;
import com.influora.service.risk.RiskContext.ActiveDeal;
import com.influora.service.risk.RiskSeverity;
import com.influora.service.risk.RiskText;
import com.influora.web.dto.meera.CreatorToolDtos.RiskFlag;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * {@code COMPETITOR_CONFLICT} (SPEC.md &sect;5.2) — an exclusivity the creator has already signed
 * still covers this brand or this category.
 *
 * <p><b>The date arithmetic, stated exactly, because an off-by-one here tells a creator she is
 * blocked when she is free.</b> The window runs from the OTHER deal's {@code appliedAt} for
 * {@code exclusivityDays} days, and the spec's condition is {@code appliedAt + exclusivityDays >
 * now} — strictly greater. So:
 *
 * <ul>
 *   <li>a 30-day window that started 29 days ago still conflicts;
 *   <li>a 30-day window that started exactly 30 days ago does NOT — the last day has elapsed and
 *       the creator is free that instant, not the next morning;
 *   <li>{@code exclusivityDays} of 0 or null never conflicts, whatever the scope says.
 * </ul>
 *
 * <p><b>The status set.</b> The spec lists CONTRACTED, IN_PROGRESS, REVIEW_PENDING,
 * REVISION_REQUESTED and "COMPLETED within its exclusivity window". The window test above already
 * carries the COMPLETED qualifier, and applying it to all five is what the trailing {@code
 * appliedAt + exclusivityDays > now} clause says — a contracted deal whose window has expired is
 * no more binding than a completed one. CANCELLED and DISPUTED are absent from the list and stay
 * absent: a deal the creator walked away from does not bind her.
 *
 * <p>When several deals conflict, the one whose window ends LAST is reported — that is the date
 * the creator actually has to wait for, and reporting the earliest would understate the block.
 */
public final class CompetitorConflictRule implements RiskRule {

    public static final String CODE = "COMPETITOR_CONFLICT";
    public static final String TITLE = "Clashes with an exclusivity you already signed";

    private static final Set<CollaborationStatus> BINDING_STATUSES =
            EnumSet.of(
                    CollaborationStatus.CONTRACTED,
                    CollaborationStatus.IN_PROGRESS,
                    CollaborationStatus.REVIEW_PENDING,
                    CollaborationStatus.REVISION_REQUESTED,
                    CollaborationStatus.COMPLETED);

    @Override
    public Optional<RiskFlag> apply(RiskContext ctx) {
        String thisBrand = brandOf(ctx);
        String thisCategory = ctx.extraction() == null ? null : ctx.extraction().category();
        if (RiskText.blank(thisBrand) && RiskText.blank(thisCategory)) {
            return Optional.empty();
        }

        Optional<ActiveDeal> conflict =
                ctx.activeDeals().stream()
                        .filter(deal -> BINDING_STATUSES.contains(deal.status()))
                        .filter(deal -> windowEnd(deal) != null)
                        .filter(deal -> windowEnd(deal).isAfter(ctx.now()))
                        .filter(deal -> covers(deal, thisBrand, thisCategory))
                        .max(Comparator.comparing(CompetitorConflictRule::windowEnd));
        if (conflict.isEmpty()) {
            return Optional.empty();
        }

        ActiveDeal deal = conflict.get();
        Instant until = windowEnd(deal);
        String untilLabel = Rendered.date(until, ctx.locale());
        String heldBy = RiskText.blank(deal.brandName()) ? "an earlier deal" : deal.brandName().trim();

        return Optional.of(
                new RiskFlag(
                        CODE,
                        RiskSeverity.WARN.name(),
                        TITLE,
                        "Conflicts with " + heldBy + " until " + untilLabel + ".",
                        null,
                        "Propose a start date after " + untilLabel + ".",
                        RiskText.data(
                                "conflict_brand", heldBy,
                                "conflict_until", untilLabel,
                                "conflict_scope", deal.exclusivityScope() == null ? null : deal.exclusivityScope().name(),
                                "conflict_deal_id", deal.collaborationId()),
                        true));
    }

    /** The instant the other deal's exclusivity stops binding, or null when it never bound. */
    private static Instant windowEnd(ActiveDeal deal) {
        Integer days = deal.exclusivityDays();
        if (days == null || days <= 0 || deal.appliedAt() == null) {
            return null;
        }
        if (deal.exclusivityScope() == null || deal.exclusivityScope() == ExclusivityScope.NONE) {
            return null;
        }
        return deal.appliedAt().plus(days, ChronoUnit.DAYS);
    }

    /** Does this still-running exclusivity actually cover the deal under evaluation? */
    private static boolean covers(ActiveDeal deal, String brand, String category) {
        return switch (deal.exclusivityScope()) {
            case NAMED_BRANDS -> RiskText.containsNormalised(deal.exclusivityBrands(), brand);
            case CATEGORY ->
                    !RiskText.blank(category)
                            && !RiskText.blank(deal.category())
                            && RiskText.norm(deal.category()).equals(RiskText.norm(category));
            case NONE -> false;
        };
    }

    private static String brandOf(RiskContext ctx) {
        if (!RiskText.blank(ctx.brandName())) {
            return ctx.brandName();
        }
        return ctx.extraction() == null ? null : ctx.extraction().brandName();
    }
}
