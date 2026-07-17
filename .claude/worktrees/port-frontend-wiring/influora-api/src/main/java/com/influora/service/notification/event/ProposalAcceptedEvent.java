package com.influora.service.notification.event;

/** #11: Creator accepts proposal (07-NOTIFICATION-SYSTEM-SPEC.md §3.2). */
public record ProposalAcceptedEvent(
        String userId,
        String workspaceId,
        String entityId,
        String creatorName,
        String campaignTitle
) implements NotificationEvent {
    @Override
    public String eventType() {
        return "proposal.accepted";
    }
}
