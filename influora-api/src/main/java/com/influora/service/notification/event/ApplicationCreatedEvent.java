package com.influora.service.notification.event;

/** #9: Creator applies to campaign (07-NOTIFICATION-SYSTEM-SPEC.md §3.2). */
public record ApplicationCreatedEvent(
        String userId,
        String workspaceId,
        String entityId,
        String creatorName,
        String campaignTitle
) implements NotificationEvent {
    @Override
    public String eventType() {
        return "application.created";
    }
}
