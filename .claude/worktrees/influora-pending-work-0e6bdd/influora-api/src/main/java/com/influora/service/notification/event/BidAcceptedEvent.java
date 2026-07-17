package com.influora.service.notification.event;

/** #4: Brand accepts creator's counter-bid (07-NOTIFICATION-SYSTEM-SPEC.md §3.1). */
public record BidAcceptedEvent(
        String userId,
        String workspaceId,
        String entityId,
        String brandName,
        String campaignTitle,
        String acceptedAmount
) implements NotificationEvent {
    @Override
    public String eventType() {
        return "bid.accepted";
    }
}
