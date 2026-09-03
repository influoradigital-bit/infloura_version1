package com.influora.service.notification.event;

/**
 * T-CREATORCONNECT-0902 — a brand workspace asked the admin team to connect with an external
 * (Meta-sourced, non-member) creator. Admin-only, email-only (no in-app notification model for
 * admins — {@code NotificationListener} sends {@code admin.creator_connection_requested} directly
 * via {@code Msg91EmailClient}, bypassing the per-user {@code EmailOutbox} pipeline the same way
 * {@code WorkspaceMemberService#sendInviteEmailDirect} does for a pre-account recipient).
 * {@code userId()} is the requesting brand user only for idempotency-key/event-plumbing purposes —
 * no in-app notification is ever created for this event.
 */
public record CreatorConnectionRequestedEvent(
        String userId,
        String workspaceId,
        String entityId,
        String brandName,
        String igUsername,
        Long followers,
        String message)
        implements NotificationEvent {
    @Override
    public String eventType() {
        return "admin.creator_connection_requested";
    }
}
