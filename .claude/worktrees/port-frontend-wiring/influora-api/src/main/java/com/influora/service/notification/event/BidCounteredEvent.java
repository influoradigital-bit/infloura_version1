package com.influora.service.notification.event;

/** #10: Creator sends counter-bid (07-NOTIFICATION-SYSTEM-SPEC.md §3.2). */
public record BidCounteredEvent(
        String userId,
        String workspaceId,
        String entityId,
        String creatorName,
        String campaignTitle,
        String counterAmount
) implements NotificationEvent {
    @Override
    public String eventType() {
        return "bid.countered";
    }
}
