package com.influora.service.notification.event;

/** #18: Welcome after signup (07-NOTIFICATION-SYSTEM-SPEC.md §3.3). */
public record UserCreatedEvent(
        String userId,
        String workspaceId,
        String entityId,
        String userName,
        String userType
) implements NotificationEvent {
    @Override
    public String eventType() {
        return "user.created";
    }
}
