package com.influora.service.notification.event;

/**
 * Task 24 billing email — fires from two independent triggers, both intentionally publishing the
 * SAME event shape so a genuine double-fire naturally dedupes at the {@code EmailOutbox}
 * idempotency-key layer ({@code {event_type}:{entity_id}:{user_id}} — {@code entityId} here is
 * always the local {@code subscriptionId}):
 *
 * <ol>
 *   <li>{@code RazorpayWebhookController} on a verified {@code subscription.halted} webhook —
 *       Razorpay itself exhausted its dunning retries.
 *   <li>{@code SubscriptionDunningJob} — this system's OWN safety-net transition of a PAST_DUE
 *       subscription to HALTED after the local grace period, for the case where Razorpay's own
 *       halted webhook was missed/delayed.
 * </ol>
 */
public record SubscriptionHaltedEvent(
        String userId, String workspaceId, String entityId, String recipientEmail
) implements NotificationEvent {
    @Override
    public String eventType() {
        return "billing.subscription_halted";
    }
}
