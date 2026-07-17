package com.influora.service.notification.event;

/** #17: Password reset (07-NOTIFICATION-SYSTEM-SPEC.md §3.3). Email only, no in-app. */
public record PasswordResetEvent(
        String userId,
        String workspaceId,
        String entityId,
        String email,
        String resetLink
) implements NotificationEvent {
    @Override
    public String eventType() {
        return "auth.reset";
    }
}
