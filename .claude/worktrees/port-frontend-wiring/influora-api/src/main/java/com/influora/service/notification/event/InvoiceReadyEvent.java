package com.influora.service.notification.event;

/**
 * Task 24 billing email — fires on a verified {@code subscription.charged} webhook
 * ({@code RazorpayWebhookController}) once the {@link com.influora.domain.entity.Invoice} row
 * has been created (and, best-effort, its PDF stored to R2). {@code downloadUrl} is the presigned
 * R2 link (nullable — R2 may be unconfigured, mirrors {@code ContractSignedEvent#downloadUrl}'s
 * null-when-unavailable convention) and {@code amountInPaise} is the server-derived amount
 * actually charged, taken straight from the webhook payload, never recomputed client-side.
 */
public record InvoiceReadyEvent(
        String userId,
        String workspaceId,
        String entityId,
        String recipientEmail,
        String downloadUrl,
        long amountInPaise
) implements NotificationEvent {
    @Override
    public String eventType() {
        return "billing.invoice_ready";
    }
}
