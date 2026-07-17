package com.influora.service.notification;

import com.influora.service.notification.event.ApplicationCreatedEvent;
import com.influora.service.notification.event.AuthOtpEvent;
import com.influora.service.notification.event.BidAcceptedEvent;
import com.influora.service.notification.event.BidCounteredEvent;
import com.influora.service.notification.event.CampaignCreatedEvent;
import com.influora.service.notification.event.CampaignRecommendedEvent;
import com.influora.service.notification.event.ContractPendingSignatureEvent;
import com.influora.service.notification.event.ContractSignedEvent;
import com.influora.service.notification.event.CreatorFirstMessageEvent;
import com.influora.service.notification.event.CreditsExhaustedEvent;
import com.influora.service.notification.event.CreditsResetEvent;
import com.influora.service.notification.event.DeliverableSubmittedEvent;
import com.influora.service.notification.event.EscrowFundedEvent;
import com.influora.service.notification.event.FirstMessageSentEvent;
import com.influora.service.notification.event.KycApprovedEvent;
import com.influora.service.notification.event.KycRejectedEvent;
import com.influora.service.notification.event.MonthlyStatementEvent;
import com.influora.service.notification.event.PasswordResetEvent;
import com.influora.service.notification.event.PayoutReleasedEvent;
import com.influora.service.notification.event.ProposalAcceptedEvent;
import com.influora.service.notification.event.ProposalSentEvent;
import com.influora.service.notification.event.ShipmentCreatedEvent;
import com.influora.service.notification.event.ShipmentReceivedEvent;
import com.influora.service.notification.event.SiteAnalyzedEvent;
import com.influora.service.notification.event.UserCreatedEvent;
import com.influora.service.notification.event.WalletLowBalanceEvent;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Listens to all domain events and routes them to {@link NotificationService}. Each event type
 * is mapped to its notification title, body template, and email template key per
 * 07-NOTIFICATION-SYSTEM-SPEC.md.
 *
 * <p>All handlers are async to avoid blocking the domain service that publishes the event.
 */
@Component
public class NotificationListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationListener.class);

    private final NotificationService notificationService;

    public NotificationListener(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    // ========== Brand -> Creator events (1-8) ==========

    @Async
    @EventListener
    public void on(CampaignCreatedEvent event) {
        notificationService.notify(
                event,
                "New campaign in your category",
                String.format(
                        "%s just launched \"%s\" - check it out!", event.brandName(), event.campaignTitle()),
                "/creator/campaigns/" + event.entityId(),
                null, // Email address would come from user lookup in real impl
                "creator.campaign_match",
                Map.of(
                        "brand_name", event.brandName(),
                        "campaign_title", event.campaignTitle(),
                        "category", event.category()));
    }

    @Async
    @EventListener
    public void on(FirstMessageSentEvent event) {
        notificationService.notify(
                event,
                "New message from " + event.brandName(),
                event.brandName() + " started a conversation with you",
                "/creator/messages/" + event.entityId(),
                null,
                "creator.new_conversation",
                Map.of("brand_name", event.brandName()));
    }

    @Async
    @EventListener
    public void on(ProposalSentEvent event) {
        notificationService.notify(
                event,
                "New proposal received",
                String.format(
                        "%s sent you a proposal for \"%s\" - %s",
                        event.brandName(), event.campaignTitle(), event.proposedAmount()),
                "/creator/proposals/" + event.entityId(),
                null,
                "creator.proposal_received",
                Map.of(
                        "brand_name", event.brandName(),
                        "campaign_title", event.campaignTitle(),
                        "proposed_amount", event.proposedAmount()));
    }

    @Async
    @EventListener
    public void on(BidAcceptedEvent event) {
        notificationService.notify(
                event,
                "Your bid was accepted!",
                String.format(
                        "%s accepted your bid for \"%s\" at %s",
                        event.brandName(), event.campaignTitle(), event.acceptedAmount()),
                "/creator/collaborations/" + event.entityId(),
                null,
                "creator.bid_accepted",
                Map.of(
                        "brand_name", event.brandName(),
                        "campaign_title", event.campaignTitle(),
                        "accepted_amount", event.acceptedAmount()));
    }

    @Async
    @EventListener
    public void on(EscrowFundedEvent event) {
        notificationService.notify(
                event,
                "Campaign is live!",
                String.format(
                        "%s funded the escrow for \"%s\" - you're good to go!",
                        event.brandName(), event.campaignTitle()),
                "/creator/collaborations/" + event.entityId(),
                null,
                "creator.campaign_live",
                Map.of("brand_name", event.brandName(), "campaign_title", event.campaignTitle()));
    }

    @Async
    @EventListener
    public void on(ShipmentCreatedEvent event) {
        notificationService.notify(
                event,
                "Product shipped!",
                String.format("%s shipped \"%s\" - %s", event.brandName(), event.productName(), event.trackingUrl()),
                "/creator/shipments/" + event.entityId(),
                null,
                "creator.product_shipped",
                Map.of(
                        "brand_name", event.brandName(),
                        "product_name", event.productName(),
                        "tracking_url", event.trackingUrl()));
    }

    @Async
    @EventListener
    public void on(ContractPendingSignatureEvent event) {
        notificationService.notify(
                event,
                "Contract ready for signature",
                String.format(
                        "Please sign the contract for \"%s\" with %s",
                        event.campaignTitle(), event.brandName()),
                "/creator/contracts/" + event.entityId(),
                null,
                "creator.sign_contract",
                Map.of("brand_name", event.brandName(), "campaign_title", event.campaignTitle()));
    }

    @Async
    @EventListener
    public void on(PayoutReleasedEvent event) {
        notificationService.notify(
                event,
                "Payment released!",
                String.format(
                        "%s released %s for \"%s\"", event.brandName(), event.amount(), event.campaignTitle()),
                "/creator/wallet",
                null,
                "creator.payout_released",
                Map.of(
                        "brand_name", event.brandName(),
                        "campaign_title", event.campaignTitle(),
                        "amount", event.amount()));
    }

    // ========== Creator -> Brand events (9-15) ==========

    @Async
    @EventListener
    public void on(ApplicationCreatedEvent event) {
        notificationService.notify(
                event,
                "New application received",
                String.format("%s applied to \"%s\"", event.creatorName(), event.campaignTitle()),
                "/brand/campaigns/" + event.entityId() + "/applications",
                null,
                "brand.new_application",
                Map.of("creator_name", event.creatorName(), "campaign_title", event.campaignTitle()));
    }

    @Async
    @EventListener
    public void on(BidCounteredEvent event) {
        notificationService.notify(
                event,
                "Counter-bid received",
                String.format(
                        "%s countered with %s for \"%s\"",
                        event.creatorName(), event.counterAmount(), event.campaignTitle()),
                "/brand/proposals/" + event.entityId(),
                null,
                "brand.counter_bid",
                Map.of(
                        "creator_name", event.creatorName(),
                        "campaign_title", event.campaignTitle(),
                        "counter_amount", event.counterAmount()));
    }

    @Async
    @EventListener
    public void on(ProposalAcceptedEvent event) {
        notificationService.notify(
                event,
                "Proposal accepted!",
                String.format("%s accepted your proposal for \"%s\"", event.creatorName(), event.campaignTitle()),
                "/brand/collaborations/" + event.entityId(),
                null,
                "brand.proposal_accepted",
                Map.of("creator_name", event.creatorName(), "campaign_title", event.campaignTitle()));
    }

    @Async
    @EventListener
    public void on(ContractSignedEvent event) {
        notificationService.notify(
                event,
                "Contract signed",
                String.format("%s signed the contract for \"%s\"", event.creatorName(), event.campaignTitle()),
                "/brand/contracts/" + event.entityId(),
                null,
                "brand.contract_signed",
                Map.of("creator_name", event.creatorName(), "campaign_title", event.campaignTitle()));
    }

    @Async
    @EventListener
    public void on(DeliverableSubmittedEvent event) {
        notificationService.notify(
                event,
                "Deliverable submitted",
                String.format(
                        "%s submitted a %s for \"%s\"",
                        event.creatorName(), event.deliverableType(), event.campaignTitle()),
                "/brand/deliverables/" + event.entityId(),
                null,
                "brand.deliverable_ready",
                Map.of(
                        "creator_name", event.creatorName(),
                        "campaign_title", event.campaignTitle(),
                        "deliverable_type", event.deliverableType()));
    }

    @Async
    @EventListener
    public void on(ShipmentReceivedEvent event) {
        notificationService.notify(
                event,
                "Product received",
                String.format("%s confirmed receipt of \"%s\"", event.creatorName(), event.productName()),
                "/brand/shipments/" + event.entityId(),
                null,
                "brand.product_received",
                Map.of("creator_name", event.creatorName(), "product_name", event.productName()));
    }

    @Async
    @EventListener
    public void on(CreatorFirstMessageEvent event) {
        notificationService.notify(
                event,
                "New message from " + event.creatorName(),
                event.creatorName() + " started a conversation with you",
                "/brand/messages/" + event.entityId(),
                null,
                "brand.new_conversation",
                Map.of("creator_name", event.creatorName()));
    }

    // ========== System events (16-22) ==========

    @Async
    @EventListener
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
    @EventListener
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
    @EventListener
    public void on(UserCreatedEvent event) {
        String templateKey = "brand".equals(event.userType()) ? "welcome.brand" : "welcome.creator";
        notificationService.notify(
                event,
                "Welcome to Influora!",
                "We're excited to have you on board, " + event.userName() + "!",
                "/dashboard",
                null,
                templateKey,
                Map.of("user_name", event.userName(), "user_type", event.userType()));
    }

    @Async
    @EventListener
    public void on(KycApprovedEvent event) {
        notificationService.notify(
                event,
                "KYC Approved",
                "Congratulations! Your KYC verification is complete.",
                "/creator/profile",
                null,
                "creator.kyc_approved",
                Map.of("creator_name", event.creatorName()));
    }

    @Async
    @EventListener
    public void on(KycRejectedEvent event) {
        notificationService.notify(
                event,
                "KYC Verification Issue",
                "There was an issue with your KYC: " + event.rejectionReason(),
                "/creator/profile/kyc",
                null,
                "creator.kyc_rejected",
                Map.of("creator_name", event.creatorName(), "rejection_reason", event.rejectionReason()));
    }

    @Async
    @EventListener
    public void on(WalletLowBalanceEvent event) {
        notificationService.notify(
                event,
                "Low Wallet Balance",
                "Your wallet balance is low (" + event.currentBalance() + "). Consider adding funds.",
                "/brand/wallet/add-funds",
                null,
                "brand.low_balance",
                Map.of("current_balance", event.currentBalance()));
    }

    @Async
    @EventListener
    public void on(MonthlyStatementEvent event) {
        // Email only
        notificationService.notify(
                event,
                null,
                null,
                null,
                null,
                "user.monthly_statement",
                Map.of(
                        "statement_period", event.statementPeriod(),
                        "statement_url", event.statementUrl()));
    }

    // ========== Meera AI events (23-26) ==========

    @Async
    @EventListener
    public void on(SiteAnalyzedEvent event) {
        // In-app only
        notificationService.notifyInApp(
                event,
                "Website Analysis Complete",
                "Meera has finished analyzing " + event.siteUrl(),
                "/brand/meera?analysis=" + event.entityId());
    }

    @Async
    @EventListener
    public void on(CampaignRecommendedEvent event) {
        // In-app only
        notificationService.notifyInApp(
                event,
                "Campaign Recommendation Ready",
                "Meera has a campaign suggestion: " + event.campaignTitle(),
                "/brand/meera?recommendation=" + event.entityId());
    }

    @Async
    @EventListener
    public void on(CreditsExhaustedEvent event) {
        notificationService.notify(
                event,
                "AI Credits Exhausted",
                "You've used all your free AI credits. Launch a campaign to unlock unlimited access!",
                "/brand/meera/credits",
                null,
                "brand.credits_exhausted",
                Map.of());
    }

    @Async
    @EventListener
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
