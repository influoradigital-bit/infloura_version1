package com.influora.service.notification.event;

/** #25: Credit exhausted (free tier) (07-NOTIFICATION-SYSTEM-SPEC.md §3.4). */
public record CreditsExhaustedEvent(
        String userId,
        String workspaceId,
        String entityId
) implements NotificationEvent {
    @Override
    public String eventType() {
        return "ai.credits_exhausted";
    }
}
