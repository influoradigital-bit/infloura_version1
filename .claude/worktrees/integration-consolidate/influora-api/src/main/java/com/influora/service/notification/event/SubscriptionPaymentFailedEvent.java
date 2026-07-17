package com.influora.service.notification.event;

/**
 * Task 24 billing email — fires on a verified {@code subscription.pending} webhook
 * ({@code RazorpayWebhookController}), the point at which {@code SubscriptionService} flips the
 * local row to {@code PAST_DUE} (Razorpay's own dunning retry cycle, not yet exhausted).
 */
public record SubscriptionPaymentFailedEvent(
        String userId, String workspaceId, String entityId, String recipientEmail
) implements NotificationEvent {
    @Override
    public String eventType() {
        return "billing.payment_failed";
    }
}
