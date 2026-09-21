package com.influora.domain.enums;

import java.util.EnumSet;
import java.util.Set;

/**
 * T-MEERA-CREATOR-PHASE-B (SPEC.md &sect;3.6) — which {@link CollaborationStatus} values count as an
 * ACTIVE deal from the creator's point of view.
 *
 * <p>This was {@code MeeraContextService.ACTIVE_DEAL_STATUSES}, {@code private static final} and in
 * {@code com.influora.service.meera}. The Phase-B creator tool executors live in
 * {@code com.influora.service.meera.tool.creator} — a different package, so even package-private
 * would not have reached it. Promoted here rather than copied: two definitions of "is this deal
 * still open" would drift, and {@code deals_summary.active_count} in the Meera context and
 * {@code get_my_deals} in the tool surface would start disagreeing in front of the creator.
 *
 * <p>Defined by COMPLEMENT, not by enumeration, which is the load-bearing part: a new
 * {@link CollaborationStatus} added later is automatically active unless it is explicitly terminal.
 * The alternative — an allow-list — silently drops a new status out of every creator's deal count
 * with nothing failing.
 */
public final class CreatorDealStatuses {

    /**
     * Terminal from the creator's point of view: the deal is over, whichever way it ended.
     * {@code DISPUTED} is terminal here because the negotiation has stopped — it is not an open deal
     * the creator can still act on through Meera.
     */
    public static final Set<CollaborationStatus> TERMINAL =
            EnumSet.of(
                    CollaborationStatus.COMPLETED,
                    CollaborationStatus.CANCELLED,
                    CollaborationStatus.DISPUTED);

    /**
     * T-MEERA-CREATOR-PHASE-A (SPEC.md 2.9, A4) — {@code deals_summary.active_count}: every
     * non-terminal collaboration status. Everything outside {@link #TERMINAL} is still an open
     * negotiation or an in-flight deal.
     */
    public static final Set<CollaborationStatus> ACTIVE = EnumSet.complementOf(EnumSet.copyOf(TERMINAL));

    private CreatorDealStatuses() {}
}
