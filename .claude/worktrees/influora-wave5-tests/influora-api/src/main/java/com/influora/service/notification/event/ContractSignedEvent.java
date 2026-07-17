package com.influora.service.notification.event;

/** #12: Creator signs contract (07-NOTIFICATION-SYSTEM-SPEC.md §3.2). */
public record ContractSignedEvent(
        String userId,
        String workspaceId,
        String entityId,
        String recipientEmail,
        String creatorName,
        String campaignTitle,
        String downloadUrl
) implements NotificationEvent {
    @Override
    public String eventType() {
        return "contract.signed";
    }
}
