package com.influora.service.risk.rules;

import com.influora.common.Rendered;
import com.influora.service.risk.DealValue;
import com.influora.service.risk.Floors;
import com.influora.service.risk.RiskContext;
import com.influora.service.risk.RiskSeverity;
import com.influora.service.risk.RiskText;
import com.influora.web.dto.brief.BriefDtos.BriefExtraction;
import com.influora.web.dto.meera.CreatorToolDtos.RiskFlag;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * {@code BARTER} (SPEC.md &sect;5.2) — the brand is paying in product.
 *
 * <p><b>What 40% means.</b> The spec puts the real value of a bartered product at about 40% of its
 * MRP. That is the resale-and-usefulness discount, not a market rate: a creator cannot pay rent
 * with a serum, and the MRP is the brand's own retail number, not what the product cost them. The
 * flag reports MRP, that 40% figure, and the cash gap to the creator's floor, so the conversation
 * starts from three numbers rather than from "is this worth it".
 *
 * <p><b>Fires for unconnected creators too.</b> Nothing in this rule reads a metric, a follower
 * count or an engagement rate — the gap is floor minus product value, and every creator has floors
 * (computed at preferences-row creation even if she never opened the settings page). A rule keyed
 * on connected Instagram data would go silent for exactly the newest creators, who are the ones
 * being offered barter.
 */
public final class BarterRule implements RiskRule {

    public static final String CODE = "BARTER";
    public static final String TITLE = "Barter only, no cash";

    /** SPEC.md &sect;5.2 — "about {40% mrp} real value". */
    private static final BigDecimal REAL_VALUE_RATIO = new BigDecimal("0.40");

    @Override
    public Optional<RiskFlag> apply(RiskContext ctx) {
        BriefExtraction extraction = ctx.extraction();
        if (extraction == null) {
            return Optional.empty();
        }
        BigDecimal mrp = extraction.barterMrpInr();
        boolean budgetAbsent = !DealValue.positive(extraction.budgetInr());
        if (!extraction.barterOnly() && !(budgetAbsent && DealValue.positive(mrp))) {
            return Optional.empty();
        }

        BigDecimal floorTotal = Floors.totalFor(ctx);
        String renderedFloor = DealValue.positive(floorTotal) ? Rendered.money(floorTotal, ctx.locale()) : null;

        if (!DealValue.positive(mrp)) {
            // Barter declared but no product value stated. Still worth the flag — the creator needs
            // to know cash is not on the table — but every number below would be fiction.
            return Optional.of(
                    new RiskFlag(
                            CODE,
                            RiskSeverity.WARN.name(),
                            TITLE,
                            "The brand is offering product instead of money, and has not said what it is worth.",
                            null,
                            "Ask for the product's retail value, then compare it against your floor.",
                            RiskText.data("floor_total", renderedFloor, "mrp_stated", "false"),
                            true));
        }

        BigDecimal realValue = mrp.multiply(REAL_VALUE_RATIO).setScale(0, RoundingMode.HALF_UP);
        BigDecimal gap = floorTotal.subtract(realValue).max(BigDecimal.ZERO);
        String renderedMrp = Rendered.money(mrp, ctx.locale());
        String renderedReal = Rendered.money(realValue, ctx.locale());
        String renderedGap = Rendered.money(gap, ctx.locale());

        String action =
                renderedFloor == null
                        ? "Offer 1 reel plus 1 story on the product, and price anything beyond that in cash."
                        : "Offer 1 reel plus 1 story on the product; 3 reels is " + renderedFloor + ".";

        return Optional.of(
                new RiskFlag(
                        CODE,
                        RiskSeverity.WARN.name(),
                        TITLE,
                        "Product worth "
                                + renderedMrp
                                + ", about "
                                + renderedReal
                                + " real value; cash gap "
                                + renderedGap
                                + ".",
                        RiskText.APPROX + " " + renderedGap + " short of your floor",
                        action,
                        RiskText.data(
                                "barter_mrp", renderedMrp,
                                "real_value", renderedReal,
                                "cash_gap", renderedGap,
                                "floor_total", renderedFloor),
                        true));
    }
}
