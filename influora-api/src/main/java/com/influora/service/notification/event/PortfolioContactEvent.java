package com.influora.service.notification.event;

/**
 * P2-10: Portfolio contact form submission.
 * Sent when a brand/visitor submits the contact form on a creator's public portfolio page.
 */
public record PortfolioContactEvent(
        String userId,
        String workspaceId,
        String entityId,
        String senderName,
        String senderEmail,
        String message
) implements NotificationEvent {
    @Override
    public String eventType() {
        return "portfolio.contact";
    }
}
