package com.influora.service.notification.event;

/** #20: KYC rejected (07-NOTIFICATION-SYSTEM-SPEC.md §3.3). */
public record KycRejectedEvent(
        String userId,
        String workspaceId,
        String entityId,
        String creatorName,
        String rejectionReason
) implements NotificationEvent {
    @Override
    public String eventType() {
        return "kyc.rejected";
    }
}
