package com.influora.service.risk.rules;

import com.influora.service.risk.RiskContext;
import com.influora.web.dto.meera.CreatorToolDtos.RiskFlag;
import java.util.Optional;

/**
 * T-MEERA-CREATOR-PHASE-B (SPEC.md &sect;5.1) — one rule from the &sect;5.2 table, as a pure
 * function of {@link RiskContext}.
 *
 * <p><b>Pure means pure.</b> An implementation may not inject a repository, read the clock (it is
 * given {@code ctx.now()}), or write anything — including the {@code OFF_PLATFORM_HINT} audit row,
 * which {@code DealRiskService} writes after the fact precisely so the rule that detects it stays
 * a function. That is what makes "one firing and one non-firing test per rule" a complete
 * statement about the rule rather than about its wiring.
 *
 * <p>Severity is set to the rule's BASE severity from the &sect;5.2 table.
 * {@code DealRiskService} applies the deal-value escalation centrally afterwards, so no
 * implementation reads {@code DealValue.Band} to decide its own severity.
 */
@FunctionalInterface
public interface RiskRule {

    /** @return the flag, or empty when this rule has nothing to say about this deal */
    Optional<RiskFlag> apply(RiskContext ctx);
}
