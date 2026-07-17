package com.influora.service.notification.event;

/** #8: Payment released (milestone/final) (07-NOTIFICATION-SYSTEM-SPEC.md §3.1). */
public record PayoutReleasedEvent(
        String userId,
        String workspaceId,
        String entityId,
        String brandName,
        String campaignTitle,
        String amount
) implements NotificationEvent {
    @Override
    public String eventType() {
        return "payout.released";
    }
}
