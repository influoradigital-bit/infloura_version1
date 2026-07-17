package com.influora.service.notification.event;

/** #15: Creator sends first message (07-NOTIFICATION-SYSTEM-SPEC.md §3.2). */
public record CreatorFirstMessageEvent(
        String userId,
        String workspaceId,
        String entityId,
        String creatorName
) implements NotificationEvent {
    @Override
    public String eventType() {
        return "message.first";
    }
}
