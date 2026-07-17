package com.influora.service.notification.event;

/**
 * Marker interface for all notification events (Domain B, 07-NOTIFICATION-SYSTEM-SPEC.md).
 * All concrete event records implement this so {@code NotificationListener} can process them
 * uniformly via Spring's {@code @EventListener}.
 */
public sealed interface NotificationEvent permits
        // Brand -> Creator events (1-8)
        CampaignCreatedEvent,
        FirstMessageSentEvent,
        ProposalSentEvent,
        BidAcceptedEvent,
        EscrowFundedEvent,
        ShipmentCreatedEvent,
        ContractPendingSignatureEvent,
        PayoutReleasedEvent,
        // Creator -> Brand events (9-15)
        ApplicationCreatedEvent,
        BidCounteredEvent,
        ProposalAcceptedEvent,
        ContractSignedEvent,
        DeliverableSubmittedEvent,
        ShipmentReceivedEvent,
        CreatorFirstMessageEvent,
        // System events (16-22)
        AuthOtpEvent,
        PasswordResetEvent,
        UserCreatedEvent,
        KycApprovedEvent,
        KycRejectedEvent,
        WalletLowBalanceEvent,
        MonthlyStatementEvent,
        // Meera AI events (23-26, M2.5)
        SiteAnalyzedEvent,
        CampaignRecommendedEvent,
        CreditsExhaustedEvent,
        CreditsResetEvent,
        // Billing / contract / portfolio events (27-31)
        ContractReadyForEscrowEvent,
        InvoiceReadyEvent,
        PortfolioContactEvent,
        SubscriptionHaltedEvent,
        SubscriptionPaymentFailedEvent {

    /** The event type string for routing (e.g., "campaign.created"). */
    String eventType();

    /** The user ID who should receive the notification. */
    String userId();

    /** Optional workspace ID for tenant-scoped events (may be null for user-level). */
    String workspaceId();

    /** The entity ID (campaign, proposal, etc.) for idempotency and deep-linking. */
    String entityId();
}
