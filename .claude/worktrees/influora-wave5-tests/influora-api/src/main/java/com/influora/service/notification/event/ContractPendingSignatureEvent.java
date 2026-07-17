package com.influora.service.notification.event;

/** #7: Contract ready for signature (07-NOTIFICATION-SYSTEM-SPEC.md §3.1). */
public record ContractPendingSignatureEvent(
        String userId,
        String workspaceId,
        String entityId,
        String brandName,
        String campaignTitle
) implements NotificationEvent {
    @Override
    public String eventType() {
        return "contract.pending_signature";
    }
}
