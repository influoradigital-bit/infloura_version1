package com.influora.service.notification.event;

/** Brand prompt to fund escrow after both parties have signed the contract. */
public record ContractReadyForEscrowEvent(
        String userId,
        String workspaceId,
        String entityId,
        String campaignTitle
) implements NotificationEvent {
    @Override
    public String eventType() {
        return "contract.ready_for_escrow";
    }
}
