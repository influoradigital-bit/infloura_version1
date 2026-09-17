package com.influora.service.billing;

import com.influora.service.BrandContextService;
import com.influora.service.BrandContextService.BillingRecipient;
import com.influora.service.notification.event.SubscriptionHaltedEvent;
import com.influora.service.notification.event.SubscriptionPaymentFailedEvent;
import org.springframework.context.ApplicationEventPublisher;

/**
 * [Kabir S2 review] Shared "resolve the billing recipient, skip silently if none, construct the
 * event, publish it" logic for the two subscription billing emails that fire from MULTIPLE
 * independent triggers — {@link SubscriptionHaltedEvent} and {@link SubscriptionPaymentFailedEvent}
 * — both already documented as deliberately sharing one event shape per trigger so a genuine
 * double-fire naturally dedupes at the {@code EmailOutbox} idempotency-key layer instead of
 * double-emailing the brand (see those events' own class javadoc).
 *
 * <p>Before this class existed, the logic lived only as a private method pair on {@code
 * RazorpayWebhookController} (the real-webhook trigger), with a second, independently-maintained
 * copy already on {@code SubscriptionDunningJob#publishHaltedEmail} (the local PAST_DUE→HALTED
 * grace-period safety net). Adding {@code SubscriptionRenewalResetJob} as a THIRD trigger (S2:
 * job-driven PAST_DUE/HALTED transitions previously sent no email at all) would have made a third
 * independent copy that must agree with the other two forever — exactly the kind of drift this
 * package's other consolidations (e.g. {@code SubscriptionService#applySubscriptionWebhookUpdate}
 * being the one shared transition both the webhook and the job now call) already guard against.
 * {@code RazorpayWebhookController} and {@code SubscriptionRenewalResetJob} both call this now;
 * {@code SubscriptionDunningJob}'s pre-existing copy was left as-is (out of scope for this fix —
 * see the S2 handoff) but is a candidate to fold into this too.
 *
 * <p>Static, not a Spring bean: every caller already has its own injected {@link
 * BrandContextService}/{@link ApplicationEventPublisher}, so routing through this adds no new
 * wiring anywhere it's called from, and no caller's constructor signature has to grow just to
 * reach a bean that holds no state of its own.
 */
public final class SubscriptionBillingEmailPublisher {

    private SubscriptionBillingEmailPublisher() {}

    /**
     * {@code subscriptionId} is the identifier used as the event's {@code entityId} (the
     * EmailOutbox dedupe key component) — callers should pass whichever id the OTHER trigger for
     * this same email already uses for this row so a genuine double-fire actually dedupes; the
     * Razorpay-driven callers ({@code RazorpayWebhookController}, {@code
     * SubscriptionRenewalResetJob}) both use the Razorpay subscription id.
     */
    public static void publishHalted(
            BrandContextService brandContextService,
            ApplicationEventPublisher eventPublisher,
            String workspaceId,
            String subscriptionId) {
        BillingRecipient recipient = brandContextService.resolveBillingRecipient(workspaceId);
        if (recipient != null && recipient.email() != null) {
            eventPublisher.publishEvent(
                    new SubscriptionHaltedEvent(recipient.userId(), workspaceId, subscriptionId, recipient.email()));
        }
    }

    /** See {@link #publishHalted} javadoc for the {@code subscriptionId}/entityId contract. */
    public static void publishPaymentFailed(
            BrandContextService brandContextService,
            ApplicationEventPublisher eventPublisher,
            String workspaceId,
            String subscriptionId) {
        BillingRecipient recipient = brandContextService.resolveBillingRecipient(workspaceId);
        if (recipient != null && recipient.email() != null) {
            eventPublisher.publishEvent(
                    new SubscriptionPaymentFailedEvent(
                            recipient.userId(), workspaceId, subscriptionId, recipient.email()));
        }
    }
}
