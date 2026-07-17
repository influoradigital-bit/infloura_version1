package com.influora.service.notification.event;

/** #6: Product shipped by brand (07-NOTIFICATION-SYSTEM-SPEC.md §3.1). */
public record ShipmentCreatedEvent(
        String userId,
        String workspaceId,
        String entityId,
        String brandName,
        String productName,
        String trackingUrl
) implements NotificationEvent {
    @Override
    public String eventType() {
        return "shipment.created";
    }
}
