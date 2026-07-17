package com.influora.service.notification.event;

/** #19: KYC approved (07-NOTIFICATION-SYSTEM-SPEC.md §3.3). */
public record KycApprovedEvent(
        String userId,
        String workspaceId,
        String entityId,
        String creatorName
) implements NotificationEvent {
    @Override
    public String eventType() {
        return "kyc.approved";
    }
}
