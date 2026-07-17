package com.influora.service.notification.event;

/** #24: Campaign recommendation ready (07-NOTIFICATION-SYSTEM-SPEC.md §3.4). In-app only. */
public record CampaignRecommendedEvent(
        String userId,
        String workspaceId,
        String entityId,
        String campaignTitle
) implements NotificationEvent {
    @Override
    public String eventType() {
        return "ai.campaign_recommended";
    }
}
