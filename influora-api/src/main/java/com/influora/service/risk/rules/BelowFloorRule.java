package com.influora.service.risk.rules;

import com.influora.common.Rendered;
import com.influora.service.risk.DealValue;
import com.influora.service.risk.Floors;
import com.influora.service.risk.RiskContext;
import com.influora.service.risk.RiskSeverity;
import com.influora.service.risk.RiskText;
import com.influora.service.scoring.CreatorTiers;
import com.influora.web.dto.meera.CreatorToolDtos.RiskFlag;
import java.math.BigDecimal;
import java.util.Optional;

/**
 * {@code BELOW_FLOOR} (SPEC.md &sect;5.2) — the offer is under what this creator said she will
 * work for.
 *
 * <p>The action splits on tier, per the spec: a NANO or MICRO creator is told to cut scope to fit
 * the money (she is more likely to need the deal than to win a raise), everyone else is told to
 * counter at the floor. Both branches name the strategic-override option out loud rather than
 * pretending the floor is a hard rule — it is the creator's number and hers to break knowingly.
 */
public final class BelowFloorRule implements RiskRule {

    public static final String CODE = "BELOW_FLOOR";
    public static final String TITLE = "Offer is below your floor";

    @Override
    public Optional<RiskFlag> apply(RiskContext ctx) {
        BigDecimal floorTotal = Floors.totalFor(ctx);
        if (!DealValue.positive(floorTotal)) {
            // No floor and no quote: nothing to compare against. Silence beats a flag that says
            // "below 0".
            return Optional.empty();
        }
        // DealValue.stated, NOT DealValue.of: this rule quotes the number back to the creator as
        // "Offer is {value}", so it may only ever read money the brand actually named. See
        // DealValue#stated for what firing on our own quote's total looked like.
        BigDecimal value = DealValue.stated(ctx);
        if (value == null) {
            // A brief that states no money at all is BARTER's or VAGUE_DELIVERABLES' problem, not
            // this rule's — "your floor is 12,000 and the offer is nothing" is not a useful flag.
            return Optional.empty();
        }
        if (value.compareTo(floorTotal) >= 0) {
            return Optional.empty();
        }

        BigDecimal shortfall = floorTotal.subtract(value);
        String renderedValue = Rendered.money(value, ctx.locale());
        String renderedFloor = Rendered.money(floorTotal, ctx.locale());
        String tier = CreatorTiers.derive(ctx.profile() == null ? 0L : ctx.profile().getTotalFollowers());
        boolean scopeDown = CreatorTiers.NANO.equals(tier) || CreatorTiers.MICRO.equals(tier);

        String action =
                scopeDown
                        ? "Counter with a smaller package that fits " + renderedValue + " rather than dropping your floor."
                        : "Counter at your floor of " + renderedFloor + ", unless you are taking this one as a deliberate exception.";

        return Optional.of(
                new RiskFlag(
                        CODE,
                        RiskSeverity.CRITICAL.name(),
                        TITLE,
                        "Offer is " + renderedValue + " against your floor of " + renderedFloor + ".",
                        RiskText.APPROX + " " + Rendered.money(shortfall, ctx.locale()) + " below your floor",
                        action,
                        RiskText.data(
                                "value", renderedValue,
                                "floor_total", renderedFloor,
                                "shortfall", Rendered.money(shortfall, ctx.locale()),
                                "tier", tier,
                                "recommended_move", scopeDown ? "SCOPE_DOWN" : "COUNTER_AT_FLOOR"),
                        true));
    }
}
