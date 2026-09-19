package com.influora.service.risk;

import com.influora.web.dto.meera.CreatorToolDtos.RiskFlag;
import java.util.List;

/**
 * T-MEERA-CREATOR-PHASE-B (SPEC.md &sect;3.5) — the {@code INFO | WARN | CRITICAL} vocabulary of
 * {@link RiskFlag#severity()}, as an enum so the ordering is a language guarantee rather than three
 * string comparisons repeated in fourteen rule classes.
 *
 * <p>Declared low-to-high, so {@link Enum#compareTo} IS the severity order.
 */
public enum RiskSeverity {
    INFO,
    WARN,
    CRITICAL;

    /** One step up, saturating at {@link #CRITICAL}. */
    public RiskSeverity escalate() {
        return this == INFO ? WARN : CRITICAL;
    }

    /**
     * SPEC.md &sect;5.2 — the value scaling, applied centrally in {@code DealRiskService} rather
     * than inside each rule, so a new rule cannot forget it.
     *
     * <p><b>Escalation only.</b> A large deal raises a flag one step; a small or mid deal leaves it
     * exactly where the rule put it. The alternative — sliding both ways around MID — would
     * downgrade every CRITICAL on a sub-10,000 offer to a WARN, and the sub-10,000 offers are
     * precisely the ones a creator is most likely to accept without reading the flag.
     */
    public static RiskSeverity scaled(RiskSeverity base, DealValue.Band band) {
        return band == DealValue.Band.LARGE ? base.escalate() : base;
    }

    /**
     * {@code CheckDealRisksResult.highest_severity}.
     *
     * @return null for an empty list — the DTO is {@code @JsonInclude(NON_NULL)}, so "no flags"
     *     omits the field rather than claiming a severity of INFO that nothing actually raised
     */
    public static String highest(List<RiskFlag> flags) {
        RiskSeverity highest = null;
        for (RiskFlag flag : flags) {
            RiskSeverity severity = parse(flag.severity());
            if (severity != null && (highest == null || severity.compareTo(highest) > 0)) {
                highest = severity;
            }
        }
        return highest == null ? null : highest.name();
    }

    /** Tolerant of an unrecognised string rather than throwing — a flag is not worth a 500. */
    public static RiskSeverity parse(String value) {
        if (value == null) {
            return null;
        }
        for (RiskSeverity severity : values()) {
            if (severity.name().equals(value)) {
                return severity;
            }
        }
        return null;
    }
}
