package com.influora.service.notification.event;

/** #5: Brand funds escrow (campaign goes live) (07-NOTIFICATION-SYSTEM-SPEC.md §3.1). */
public record EscrowFundedEvent(
        String userId,
        String workspaceId,
        String entityId,
        String brandName,
        String campaignTitle
) implements NotificationEvent {
    @Override
    public String eventType() {
        return "escrow.funded";
    }
}
