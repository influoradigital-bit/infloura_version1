package com.influora.domain.enums;

/**
 * The full application/deal lifecycle taxonomy for {@link
 * com.influora.domain.entity.ApplicationHistoryEvent}. Shape is fixed by the persistent
 * application-history requirements doc — do not rename values without updating the V69
 * migration's {@code event_type} ENUM in lockstep.
 *
 * <p>Every value here is wired, at the point each already happens in reviewed code: {@link
 * #CAMPAIGN_APPLIED}, {@link #APPLICATION_RECEIVED} ({@code CreatorCampaignService#apply}),
 * {@link #APPLICATION_VIEWED} ({@code DealService#get}, brand role only), {@link
 * #APPLICATION_ACCEPTED} ({@code DealService#doAccept}), {@link #APPLICATION_REJECTED} / {@link
 * #APPLICATION_WITHDRAWN} ({@code DealService#doReject}, branched on actor — see that value's own
 * javadoc), {@link #DEAL_ROOM_ACTIVATED} / {@link #CONTRACT_GENERATED} ({@code
 * ContractService#generate}), {@link #CONTRACT_SIGNED} ({@code ContractService#doRecordSignature},
 * the branch where both signatures complete), {@link #FUND_ESCROW} ({@code
 * EscrowService#applyFunding} via {@code notifyEscrowFunded}), {@link #DELIVERABLE_SUBMITTED}
 * ({@code CreatorDeliverableService#submitForReview}), {@link #DELIVERABLE_APPROVED} ({@code
 * BrandDeliverableService#approve}), {@link #DELIVER} ({@code
 * CollaborationLifecycleService#recomputeReviewState}, only on a genuine transition into {@code
 * COMPLETED}), {@link #PAY} ({@code PayoutService#doQueuePayout}, after the RazorpayX gateway call
 * returns — the actual transition of money leaving the platform, not {@code EscrowService}'s
 * release into the creator's internal wallet).
 *
 * <p><b>Declaration order note.</b> The requirements doc's own section 4 (event-type list) and
 * section 7 (workflow stages) disagree on ordering: section 4 lists "... Deliverables, Fund
 * Escrow, Deliver, Pay" (deliverables before escrow), section 7 orders the stages "Negotiate,
 * Contract, Fund Escrow, Deliver, Pay" (escrow before deliverables). The already-shipped {@code
 * src/components/brand/deal-room/deal-room-step-progress.tsx} stepper agrees with section 7. This
 * enum's declaration order follows section 7 / the shipped stepper (escrow before deliver) — the
 * mismatch is real and unresolved in the source document, not silently picked one way.
 *
 * <p><b>{@link #APPLICATION_WITHDRAWN} is a deliberate, documented deviation from the requirements
 * doc's 13-value list</b> — that document never contemplated creator-initiated withdrawal.
 * {@code DealService#doReject} is not only the brand's rejection path; it is also the creator's
 * own "withdraw my application" path (same method, branched on {@code role}). Recording both as
 * {@code APPLICATION_REJECTED} made a creator's own withdrawal permanently read, in the audit
 * ledger, as the brand having rejected them — wrong in the data, not just on screen. {@link
 * #APPLICATION_WITHDRAWN} is recorded for the creator-initiated case; {@link
 * #APPLICATION_REJECTED} stays exactly what it was for the brand-initiated case. This is silent
 * about user-visible wording on purpose: whether a brand's decline may ever be SHOWN to a creator
 * as the word "Rejected" is a separate, open conflict between the requirements doc and a standing
 * CTO arbitration ({@code src/lib/application-status.ts}, Kabir R5) pending a human ruling — this
 * enum value fixes what is RECORDED, not what is DISPLAYED.
 */
public enum ApplicationHistoryEventType {
    CAMPAIGN_APPLIED,
    APPLICATION_RECEIVED,
    APPLICATION_VIEWED,
    APPLICATION_ACCEPTED,
    APPLICATION_REJECTED,
    /** See this enum's class javadoc — a deliberate deviation from the requirements doc. */
    APPLICATION_WITHDRAWN,
    DEAL_ROOM_ACTIVATED,
    CONTRACT_GENERATED,
    CONTRACT_SIGNED,
    FUND_ESCROW,
    DELIVERABLE_SUBMITTED,
    DELIVERABLE_APPROVED,
    DELIVER,
    PAY
}
