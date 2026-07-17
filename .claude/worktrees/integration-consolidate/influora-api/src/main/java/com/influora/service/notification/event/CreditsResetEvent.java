package com.influora.service.notification.event;

/** #26: Credits reset (after go-live) (07-NOTIFICATION-SYSTEM-SPEC.md §3.4). In-app only. */
public record CreditsResetEvent(
        String userId,
        String workspaceId,
        String entityId,
        int newAllotment
) implements NotificationEvent {
    @Override
    public String eventType() {
        return "ai.credits_reset";
    }
}
