package com.influora.service.notification.event;

/**
 * T-CREATORCONNECT-0902 — the creator a brand asked to connect with is now a real, JOINED
 * Influora creator (published by {@code ExternalCreatorLinkService#onCreatorIdentified}, one per
 * {@code creator_connection_requests} row flipped to JOINED). Both channels: in-app notification
 * + {@code brand.connected_creator_joined} email to the requesting brand user, who IS a real
 * Influora {@code User} — unlike the two admin/creator-facing events in this package, this one
 * goes through the standard {@code NotificationService#notify} pipeline.
 */
public record ConnectedCreatorJoinedEvent(
        String userId,
        String workspaceId,
        String entityId,
        String creatorName,
        String igUsername,
        String creatorProfileId)
        implements NotificationEvent {
    @Override
    public String eventType() {
        return "brand.connected_creator_joined";
    }
}
