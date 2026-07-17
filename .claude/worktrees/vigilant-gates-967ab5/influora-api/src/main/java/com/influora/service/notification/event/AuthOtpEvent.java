package com.influora.service.notification.event;

/** #16: OTP for signup/login (07-NOTIFICATION-SYSTEM-SPEC.md §3.3). Email only, no in-app. */
public record AuthOtpEvent(
        String userId,
        String workspaceId,
        String entityId,
        String email,
        String otp
) implements NotificationEvent {
    @Override
    public String eventType() {
        return "auth.otp";
    }
}
