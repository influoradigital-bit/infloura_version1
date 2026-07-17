package com.influora.service.notification.event;

/** #13: Creator submits deliverable (07-NOTIFICATION-SYSTEM-SPEC.md §3.2). */
public record DeliverableSubmittedEvent(
        String userId,
        String workspaceId,
        String entityId,
        String creatorName,
        String campaignTitle,
        String deliverableType
) implements NotificationEvent {
    @Override
    public String eventType() {
        return "deliverable.submitted";
    }
}
