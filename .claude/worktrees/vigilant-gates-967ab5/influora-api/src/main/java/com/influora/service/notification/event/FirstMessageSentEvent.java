package com.influora.service.notification.event;

/** #2: Brand sends first message (07-NOTIFICATION-SYSTEM-SPEC.md §3.1). */
public record FirstMessageSentEvent(
        String userId,
        String workspaceId,
        String entityId,
        String brandName
) implements NotificationEvent {
    @Override
    public String eventType() {
        return "message.first";
    }
}
