package com.influora.service.notification.event;

/** #3: Brand sends proposal/bid (07-NOTIFICATION-SYSTEM-SPEC.md §3.1). */
public record ProposalSentEvent(
        String userId,
        String workspaceId,
        String entityId,
        String brandName,
        String campaignTitle,
        String proposedAmount
) implements NotificationEvent {
    @Override
    public String eventType() {
        return "proposal.sent";
    }
}
