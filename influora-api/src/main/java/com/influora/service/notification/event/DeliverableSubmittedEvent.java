package com.influora.service.notification.event;

/** #13: Creator submits deliverable (07-NOTIFICATION-SYSTEM-SPEC.md §3.2). */
public record DeliverableSubmittedEvent(
        String userId,
        String workspaceId,
        String entityId,
        // The deal this deliverable belongs to. entityId is the DELIVERABLE id, which no brand route
        // takes; the brand reviews deliverables inside the deal room, keyed by collaboration id.
        String collaborationId,
        String creatorName,
        String campaignTitle,
        String deliverableType
) implements NotificationEvent {
    @Override
    public String eventType() {
        return "deliverable.submitted";
    }
}
