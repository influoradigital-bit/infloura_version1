package com.influora.service.notification.event;

/** #14: Creator confirms product received (07-NOTIFICATION-SYSTEM-SPEC.md §3.2). */
public record ShipmentReceivedEvent(
        String userId,
        String workspaceId,
        String entityId,
        String creatorName,
        String productName
) implements NotificationEvent {
    @Override
    public String eventType() {
        return "shipment.received";
    }
}
