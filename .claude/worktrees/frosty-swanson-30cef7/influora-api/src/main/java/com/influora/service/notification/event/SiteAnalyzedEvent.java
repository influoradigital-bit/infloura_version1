package com.influora.service.notification.event;

/** #23: Website analysis complete (07-NOTIFICATION-SYSTEM-SPEC.md §3.4). In-app only. */
public record SiteAnalyzedEvent(
        String userId,
        String workspaceId,
        String entityId,
        String siteUrl
) implements NotificationEvent {
    @Override
    public String eventType() {
        return "ai.site_analyzed";
    }
}
