package com.influora.service.notification.event;

/** #1: Campaign created in creator's category (07-NOTIFICATION-SYSTEM-SPEC.md §3.1). */
public record CampaignCreatedEvent(
        String userId,
        String workspaceId,
        String entityId,
        String campaignTitle,
        String brandName,
        String category
) implements NotificationEvent {
    @Override
    public String eventType() {
        return "campaign.created";
    }
}
