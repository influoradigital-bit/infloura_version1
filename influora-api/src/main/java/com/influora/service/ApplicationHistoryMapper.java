package com.influora.service;

import com.influora.domain.entity.ApplicationHistoryEvent;
import com.influora.domain.enums.ApplicationHistoryEventType;
import com.influora.web.dto.creatorcampaign.CreatorApplicationDtos.ApplicationHistoryEventItem;

/** Maps {@link ApplicationHistoryEvent} to the wire DTO for {@code GET
 * /creator/applications/{dealId}/history}. */
public final class ApplicationHistoryMapper {

    private ApplicationHistoryMapper() {}

    public static ApplicationHistoryEventItem toItem(ApplicationHistoryEvent event) {
        return new ApplicationHistoryEventItem(
                event.getHistoryId(),
                event.getCampaignId(),
                event.getApplicationId(),
                event.getDealRoomId(),
                event.getEventType().name(),
                event.getEventStatus().name(),
                event.getActorType().name(),
                event.getActorId(),
                event.getDescription(),
                event.getCreatedAt(),
                event.getMetadata(),
                event.getTargetRoute(),
                event.getTargetId(),
                DealPhaseCalculator.computeDealPhase(
                        event.getEventStatus(), contractStatusForPhase(event.getEventType())));
    }

    /**
     * Requirements review, requirement #5 — {@code CONTRACT_GENERATED}/{@code CONTRACT_SIGNED}
     * must pass DealPhaseCalculator a real contract-status value, not {@code null}. Derived from
     * the event TYPE (each of these two events only ever fires at one well-defined moment in
     * {@code ContractService} — see {@code DealPhaseCalculator}'s javadoc for why that is more
     * correct than a live re-read of {@code Contract.status}, and why it happens not to change
     * the computed phase for either event today).
     */
    private static String contractStatusForPhase(ApplicationHistoryEventType eventType) {
        return switch (eventType) {
            // ContractService#generate always creates a fresh Contract with zero signatures —
            // the frontend's mapDealApiContractStatus maps that DRAFT status to 'generated'.
            case CONTRACT_GENERATED -> "generated";
            // ContractService#doRecordSignature's fully-signed branch fires at the exact instant
            // both signatures land, structurally before escrow could possibly be funded yet
            // (EscrowService#assertContractActiveForMilestone requires the contract be fully
            // signed first) — mapDealApiContractStatus maps ACTIVE+escrowFunded=false to
            // 'creator_signed'.
            case CONTRACT_SIGNED -> "creator_signed";
            default -> null;
        };
    }
}
