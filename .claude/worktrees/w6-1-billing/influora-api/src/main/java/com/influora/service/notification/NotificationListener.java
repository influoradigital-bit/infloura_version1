package com.influora.service.notification;

import com.influora.domain.entity.User;
import com.influora.repository.UserRepository;
import com.influora.service.notification.event.ApplicationCreatedEvent;
import com.influora.service.notification.event.AuthOtpEvent;
import com.influora.service.notification.event.BidAcceptedEvent;
import com.influora.service.notification.event.BidCounteredEvent;
import com.influora.service.notification.event.CampaignCreatedEvent;
import com.influora.service.notification.event.CampaignRecommendedEvent;
import com.influora.service.notification.event.ContractPendingSignatureEvent;
import com.influora.service.notification.event.ContractReadyForEscrowEvent;
import com.influora.service.notification.event.ContractSignedEvent;
import com.influora.service.notification.event.CreatorFirstMessageEvent;
import com.influora.service.notification.event.CreditsExhaustedEvent;
import com.influora.service.notification.event.CreditsResetEvent;
import com.influora.service.notification.event.DeliverableSubmittedEvent;
import com.influora.service.notification.event.EscrowFundedEvent;
import com.influora.service.notification.event.FirstMessageSentEvent;
import com.influora.service.notification.event.InvoiceReadyEvent;
import com.influora.service.notification.event.KycApprovedEvent;
import com.influora.service.notification.event.KycRejectedEvent;
import com.influora.service.notification.event.MonthlyStatementEvent;
import com.influora.service.notification.event.PasswordResetEvent;
import com.influora.service.notification.event.PayoutReleasedEvent;
import com.influora.service.notification.event.PortfolioContactEvent;
import com.influora.service.notification.event.ProposalAcceptedEvent;
import com.influora.service.notification.event.ProposalSentEvent;
import com.influora.service.notification.event.ShipmentCreatedEvent;
import com.influora.service.notification.event.ShipmentReceivedEvent;
import com.influora.service.notification.event.SiteAnalyzedEvent;
import com.influora.service.notification.event.SubscriptionHaltedEvent;
import com.influora.service.notification.event.SubscriptionPaymentFailedEvent;
import com.influora.service.notification.event.UserCreatedEvent;
import com.influora.service.notification.event.WalletLowBalanceEvent;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Listens to all domain events and routes them to {@link NotificationService}. Each event type
 * is mapped to its notification title, body template, and email template key per
 * 07-NOTIFICATION-SYSTEM-SPEC.md.
 *
 * <p>All handlers are {@code @Async} (off the publisher's request thread, {@code @EnableAsync} —
 * bc81ff3) AND {@link TransactionalEventListener} bound to {@link TransactionPhase#AFTER_COMMIT}
 * (W3-2b / D2). Plain {@code @Async @EventListener} — what this class used before — fires as soon
 * as the event is published, which can race the publisher's own {@code @Transactional} method: the
 * async handler can read/act on a row (e.g. resolve a recipient email, queue an outbox row keyed to
 * an entity id) that the surrounding transaction then rolls back, leaving a notification for
 * something that never actually happened. {@code AFTER_COMMIT} only fires once the publishing
 * transaction has actually committed, closing that race (see the {@code [D2 coupling, flagged]}
 * note this superseded in {@code EscrowService#publishPayoutReleasedEvent}).
 *
 * <p><b>The AFTER_COMMIT / no-active-transaction interaction, addressed:</b> a {@code
 * TransactionalEventListener} bound to {@code AFTER_COMMIT} is silently NEVER invoked for an event
 * published with no transaction currently active — Spring drops it, no error, no log. Every
 * publisher of a {@link com.influora.service.notification.event.NotificationEvent} in this codebase
 * was audited for this: {@code ContractService}, {@code EscrowService}, {@code DealService}, {@code
 * CreatorCampaignService}, {@code CreatorDeliverableService}, {@code PortfolioService}, and {@code
 * AuthService} publish exclusively from inside a {@code @Transactional} method, so AFTER_COMMIT is
 * guaranteed to fire for them. {@code SubscriptionDunningJob#haltOne} was the one exception — a
 * {@code @Scheduled} job method with no surrounding transaction of its own (each repository {@code
 * save} call opened and closed its own implicit transaction) — so publishing
 * {@link SubscriptionHaltedEvent} from there would have been silently dropped by this change. Fixed
 * at the source (see {@code SubscriptionDunningJob}) by wrapping the halt + publish in an explicit
 * {@code TransactionTemplate} transaction, the same idiom {@code AnalyzeSiteTriggerService} already
 * uses for exactly this reason, rather than special-casing the listener here.
 */
@Component
public class NotificationListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationListener.class);

    private final NotificationService notificationService;
    private final UserRepository userRepository;

    public NotificationListener(NotificationService notificationService, UserRepository userRepository) {
        this.notificationService = notificationService;
        this.userRepository = userRepository;
    }

    /**
     * W3-2 — every "both channels" handler below used to pass a literal {@code null} for the email
     * recipient with the comment "Email address would come from user lookup in real impl", meaning
     * the email half of every one of these notifications was silently never queued (see {@code
     * NotificationService#notify}: a null {@code toEmail} short-circuits {@code
     * queueEmailIfNotUnsubscribed} with a warning log and nothing else). This resolves the real
     * recipient from {@link UserRepository} by the event's {@code userId()} — every {@link
     * com.influora.service.notification.event.NotificationEvent} carries one. Events that already
     * carry their own explicit recipient email (OTP/password-reset/contract-signed/portfolio-contact/
     * billing events) are left alone rather than re-resolved.
     */
    private String emailOf(String userId) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        return userRepository.findById(userId).map(User::getEmail).orElse(null);
    }

    // ========== Brand -> Creator events (1-8) ==========

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(CampaignCreatedEvent event) {
        notificationService.notify(
                event,
                "New campaign in your category",
                String.format(
                        "%s just launched \"%s\" - check it out!", event.brandName(), event.campaignTitle()),
                "/creator/campaigns/" + event.entityId(),
                emailOf(event.userId()),
                "creator.campaign_match",
                Map.of(
                        "brand_name", event.brandName(),
                        "campaign_title", event.campaignTitle(),
                        "category", event.category()));
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(FirstMessageSentEvent event) {
        notificationService.notify(
                event,
                "New message from " + event.brandName(),
                event.brandName() + " started a conversation with you",
                "/creator/messages/" + event.entityId(),
                emailOf(event.userId()),
                "creator.new_conversation",
                Map.of("brand_name", event.brandName()));
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(ProposalSentEvent event) {
        notificationService.notify(
                event,
                "New proposal received",
                String.format(
                        "%s sent you a proposal for \"%s\" - %s",
                        event.brandName(), event.campaignTitle(), event.proposedAmount()),
                "/creator/proposals/" + event.entityId(),
                emailOf(event.userId()),
                "creator.proposal_received",
                Map.of(
                        "brand_name", event.brandName(),
                        "campaign_title", event.campaignTitle(),
                        "proposed_amount", event.proposedAmount()));
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(BidAcceptedEvent event) {
        notificationService.notify(
                event,
                "Your bid was accepted!",
                String.format(
                        "%s accepted your bid for \"%s\" at %s",
                        event.brandName(), event.campaignTitle(), event.acceptedAmount()),
                "/creator/collaborations/" + event.entityId(),
                emailOf(event.userId()),
                "creator.bid_accepted",
                Map.of(
                        "brand_name", event.brandName(),
                        "campaign_title", event.campaignTitle(),
                        "accepted_amount", event.acceptedAmount()));
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(EscrowFundedEvent event) {
        notificationService.notify(
                event,
                "Campaign is live!",
                String.format(
                        "%s funded the escrow for \"%s\" - you're good to go!",
                        event.brandName(), event.campaignTitle()),
                "/creator/collaborations/" + event.entityId(),
                emailOf(event.userId()),
                "creator.campaign_live",
                Map.of("brand_name", event.brandName(), "campaign_title", event.campaignTitle()));
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(ShipmentCreatedEvent event) {
        notificationService.notify(
                event,
                "Product shipped!",
                String.format("%s shipped \"%s\" - %s", event.brandName(), event.productName(), event.trackingUrl()),
                "/creator/shipments/" + event.entityId(),
                emailOf(event.userId()),
                "creator.product_shipped",
                Map.of(
                        "brand_name", event.brandName(),
                        "product_name", event.productName(),
                        "tracking_url", event.trackingUrl()));
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(ContractPendingSignatureEvent event) {
        notificationService.notify(
                event,
                "Contract ready for signature",
                String.format(
                        "Please sign the contract for \"%s\" with %s",
                        event.campaignTitle(), event.brandName()),
                "/creator/contracts/" + event.entityId(),
                emailOf(event.userId()),
                "creator.sign_contract",
                Map.of("brand_name", event.brandName(), "campaign_title", event.campaignTitle()));
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(PayoutReleasedEvent event) {
        notificationService.notify(
                event,
                "Payment released!",
                String.format(
                        "%s released %s for \"%s\"", event.brandName(), event.amount(), event.campaignTitle()),
                "/creator/wallet",
                emailOf(event.userId()),
                "creator.payout_released",
                Map.of(
                        "brand_name", event.brandName(),
                        "campaign_title", event.campaignTitle(),
                        "amount", event.amount()));
    }

    // ========== Creator -> Brand events (9-15) ==========

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(ApplicationCreatedEvent event) {
        notificationService.notify(
                event,
                "New application received",
                String.format("%s applied to \"%s\"", event.creatorName(), event.campaignTitle()),
                "/brand/campaigns/" + event.entityId() + "/applications",
                emailOf(event.userId()),
                "brand.new_application",
                Map.of("creator_name", event.creatorName(), "campaign_title", event.campaignTitle()));
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(BidCounteredEvent event) {
        notificationService.notify(
                event,
                "Counter-bid received",
                String.format(
                        "%s countered with %s for \"%s\"",
                        event.creatorName(), event.counterAmount(), event.campaignTitle()),
                "/brand/proposals/" + event.entityId(),
                emailOf(event.userId()),
                "brand.counter_bid",
                Map.of(
                        "creator_name", event.creatorName(),
                        "campaign_title", event.campaignTitle(),
                        "counter_amount", event.counterAmount()));
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(ProposalAcceptedEvent event) {
        notificationService.notify(
                event,
                "Proposal accepted!",
                String.format("%s accepted your proposal for \"%s\"", event.creatorName(), event.campaignTitle()),
                "/brand/collaborations/" + event.entityId(),
                emailOf(event.userId()),
                "brand.proposal_accepted",
                Map.of("creator_name", event.creatorName(), "campaign_title", event.campaignTitle()));
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(ContractSignedEvent event) {
        notificationService.notify(
                event,
                "Contract signed",
                String.format("%s signed the contract for \"%s\"", event.creatorName(), event.campaignTitle()),
                "/brand/contracts/" + event.entityId(),
                // This event already carries the caller-resolved recipient email (ContractService
                // knows exactly which address to use per-recipient — brand vs creator, two separate
                // events); no lookup needed or wanted here.
                event.recipientEmail(),
                "brand.contract_signed",
                Map.of("creator_name", event.creatorName(), "campaign_title", event.campaignTitle()));
    }

    /**
     * M-14 (INFLUORA-PRODUCTION-READINESS-AUDIT-2026-07-14.md) — {@code ContractReadyForEscrowEvent}
     * was published by {@code ContractService} (both parties signed) but had no listener at all, so
     * the brand never got prompted to fund escrow. The event already carries the resolved brand
     * owner's userId; email is looked up the same way every other userId-only event resolves it.
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(ContractReadyForEscrowEvent event) {
        notificationService.notify(
                event,
                "Fund escrow to get started",
                String.format(
                        "Both parties signed the contract for \"%s\" — fund escrow to begin.",
                        event.campaignTitle()),
                "/brand/contracts/" + event.entityId(),
                emailOf(event.userId()),
                "brand.contract_ready_for_escrow",
                Map.of("campaign_title", event.campaignTitle()));
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(DeliverableSubmittedEvent event) {
        notificationService.notify(
                event,
                "Deliverable submitted",
                String.format(
                        "%s submitted a %s for \"%s\"",
                        event.creatorName(), event.deliverableType(), event.campaignTitle()),
                "/brand/deliverables/" + event.entityId(),
                emailOf(event.userId()),
                "brand.deliverable_ready",
                Map.of(
                        "creator_name", event.creatorName(),
                        "campaign_title", event.campaignTitle(),
                        "deliverable_type", event.deliverableType()));
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(ShipmentReceivedEvent event) {
        notificationService.notify(
                event,
                "Product received",
                String.format("%s confirmed receipt of \"%s\"", event.creatorName(), event.productName()),
                "/brand/shipments/" + event.entityId(),
                emailOf(event.userId()),
                "brand.product_received",
                Map.of("creator_name", event.creatorName(), "product_name", event.productName()));
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(CreatorFirstMessageEvent event) {
        notificationService.notify(
                event,
                "New message from " + event.creatorName(),
                event.creatorName() + " started a conversation with you",
                "/brand/messages/" + event.entityId(),
                emailOf(event.userId()),
                "brand.new_conversation",
                Map.of("creator_name", event.creatorName()));
    }

    /**
     * P2-10 — portfolio contact form submission. {@code PortfolioService#contact} used to call
     * {@code NotificationService.notify} directly, bypassing the event bus entirely (so this event
     * class existed, was constructed, and was never actually published — the exact "6 unhandled
     * events" gap this task closes). It now publishes through {@code ApplicationEventPublisher} like
     * every other domain event, and this listener does the notify call. The event carries the
     * sender's own email (a public visitor, not a platform user) as the reply-to-style contact
     * detail — {@code emailOf} is not applicable here, the recipient is the creator ({@code
     * event.userId()}), whose email still needs the normal lookup.
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(PortfolioContactEvent event) {
        notificationService.notify(
                event,
                "New portfolio contact from " + event.senderName(),
                "You received a message via your portfolio page.",
                "/creator/inbox",
                emailOf(event.userId()),
                "portfolio.contact",
                Map.of(
                        "senderName", event.senderName(),
                        "senderEmail", event.senderEmail(),
                        "message", event.message()));
    }

    // ========== System events (16-22) ==========

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(AuthOtpEvent event) {
        // Email only - no in-app notification
        notificationService.notify(
                event,
                null, // No in-app
                null,
                null,
                event.email(),
                "auth.otp",
                Map.of("otp", event.otp()));
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(PasswordResetEvent event) {
        // Email only
        notificationService.notify(
                event,
                null,
                null,
                null,
                event.email(),
                "auth.password_reset",
                Map.of("reset_link", event.resetLink()));
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(UserCreatedEvent event) {
        String templateKey = "brand".equals(event.userType()) ? "welcome.brand" : "welcome.creator";
        notificationService.notify(
                event,
                "Welcome to Influora!",
                "We're excited to have you on board, " + event.userName() + "!",
                "/dashboard",
                emailOf(event.userId()),
                templateKey,
                Map.of("user_name", event.userName(), "user_type", event.userType()));
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(KycApprovedEvent event) {
        notificationService.notify(
                event,
                "KYC Approved",
                "Congratulations! Your KYC verification is complete.",
                "/creator/profile",
                emailOf(event.userId()),
                "creator.kyc_approved",
                Map.of("creator_name", event.creatorName()));
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(KycRejectedEvent event) {
        notificationService.notify(
                event,
                "KYC Verification Issue",
                "There was an issue with your KYC: " + event.rejectionReason(),
                "/creator/profile/kyc",
                emailOf(event.userId()),
                "creator.kyc_rejected",
                Map.of("creator_name", event.creatorName(), "rejection_reason", event.rejectionReason()));
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(WalletLowBalanceEvent event) {
        notificationService.notify(
                event,
                "Low Wallet Balance",
                "Your wallet balance is low (" + event.currentBalance() + "). Consider adding funds.",
                "/brand/wallet/add-funds",
                emailOf(event.userId()),
                "brand.low_balance",
                Map.of("current_balance", event.currentBalance()));
    }

    /**
     * Task 24 billing email — {@code SubscriptionHaltedEvent} fires from two independent triggers
     * (see the event's own javadoc): a verified Razorpay {@code subscription.halted} webhook (not
     * yet routed — W1-6, genuinely open, tracked separately) and {@code SubscriptionDunningJob}'s
     * own local grace-period safety net (wired). The event already carries the resolved billing
     * recipient's email, so no lookup.
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(SubscriptionHaltedEvent event) {
        notificationService.notify(
                event,
                "Your subscription was halted",
                "Payment retries were exhausted and your subscription has been halted. Update your"
                        + " payment method to resume service.",
                "/brand/billing",
                event.recipientEmail(),
                "billing.subscription_halted",
                Map.of());
    }

    /**
     * Task 24 billing email — fires on a verified {@code subscription.pending} webhook (see the
     * event's own javadoc). {@code RazorpayWebhookController} does not yet route {@code
     * subscription.*} events at all (W1-6, tracked separately, out of this task's scope) so there is
     * no publisher for this event yet on this branch — the listener is wired ahead of that so W1-6
     * only has to add one {@code eventPublisher.publishEvent(...)} call, not build the
     * notification path too.
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(SubscriptionPaymentFailedEvent event) {
        notificationService.notify(
                event,
                "Payment failed",
                "Your subscription payment could not be processed. We'll retry automatically, but you"
                        + " may want to update your payment method.",
                "/brand/billing",
                event.recipientEmail(),
                "billing.payment_failed",
                Map.of());
    }

    /**
     * Task 24 billing email — fires once an {@code Invoice} row exists for a charged subscription
     * (see the event's own javadoc). Same W1-6 dependency as {@link #on(SubscriptionPaymentFailedEvent)}
     * — no publisher yet on this branch (subscription webhooks aren't routed), listener wired ahead
     * of that work.
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(InvoiceReadyEvent event) {
        notificationService.notify(
                event,
                "Your invoice is ready",
                "Your invoice is ready to download.",
                event.downloadUrl(),
                event.recipientEmail(),
                "billing.invoice_ready",
                Map.of(
                        "download_url", event.downloadUrl() != null ? event.downloadUrl() : "",
                        "amount_in_paise", event.amountInPaise()));
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(MonthlyStatementEvent event) {
        // Email only
        notificationService.notify(
                event,
                null,
                null,
                null,
                emailOf(event.userId()),
                "user.monthly_statement",
                Map.of(
                        "statement_period", event.statementPeriod(),
                        "statement_url", event.statementUrl()));
    }

    // ========== Meera AI events (23-26) ==========

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(SiteAnalyzedEvent event) {
        // In-app only
        notificationService.notifyInApp(
                event,
                "Website Analysis Complete",
                "Meera has finished analyzing " + event.siteUrl(),
                "/brand/meera?analysis=" + event.entityId());
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(CampaignRecommendedEvent event) {
        // In-app only
        notificationService.notifyInApp(
                event,
                "Campaign Recommendation Ready",
                "Meera has a campaign suggestion: " + event.campaignTitle(),
                "/brand/meera?recommendation=" + event.entityId());
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(CreditsExhaustedEvent event) {
        notificationService.notify(
                event,
                "AI Credits Exhausted",
                "You've used all your free AI credits. Launch a campaign to unlock unlimited access!",
                "/brand/meera/credits",
                emailOf(event.userId()),
                "brand.credits_exhausted",
                Map.of());
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(CreditsResetEvent event) {
        // In-app only
        notificationService.notifyInApp(
                event,
                "AI Credits Reset",
                String.format(
                        "Your AI credits have been reset to %d for the new billing cycle.", event.newAllotment()),
                "/brand/meera/credits");
    }
}
