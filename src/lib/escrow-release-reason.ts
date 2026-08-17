/**
 * F-0223 — turning `paymentHeldReason` into something a brand can act on.
 *
 * Approving a deliverable is the act that pays the creator. `EscrowService#tryReleaseOnApproval`
 * attempts the release and, on eight distinct conditions, skips it and returns instead of
 * throwing — the approval still succeeds. Before this, every one of those was reported to the
 * brand as a plain "Approved", so a deliverable could be signed off with the creator paid
 * nothing and no one told.
 *
 * These strings are deliberately about MONEY and WHAT TO DO, not about the enum. "The milestone
 * has no escrow funded" tells a brand nothing; "Fund escrow for this milestone and the payment
 * will release" tells them the next action.
 */

/** Server-side reason codes from `EscrowService.ReleaseOutcome#heldReason`. */
export type PaymentHeldReason =
  | 'NO_MILESTONE'
  | 'MILESTONE_NOT_FOUND'
  | 'MILESTONE_NOT_FUNDED'
  | 'INVALID_ESCROW_STATE'
  | 'RELEASE_CONDITION_NOT_MET'
  | 'ESCROW_BLOCKED_BY_DISPUTE'
  | 'ESCROW_NOT_FOUND'
  | 'COLLABORATION_NOT_FOUND'
  | 'NOT_APPLICABLE';

const REASONS: Record<PaymentHeldReason, string> = {
  // The F-0222 signature: funded campaign-level, so the hold never reached the milestone.
  MILESTONE_NOT_FUNDED:
    'Escrow was never funded for this milestone, so nothing was paid out. Fund it from the deal room and the payment will release.',
  ESCROW_NOT_FOUND:
    'No escrow hold exists for this milestone, so nothing was paid out. Fund it from the deal room and the payment will release.',
  NO_MILESTONE:
    'This deliverable is not linked to a payment milestone, so no payment was released. It needs a contract with milestones before it can pay out.',
  MILESTONE_NOT_FOUND:
    'The payment milestone behind this deliverable could not be found, so nothing was paid out. Support needs to look at this deal.',
  COLLABORATION_NOT_FOUND:
    'The deal behind this deliverable could not be found, so nothing was paid out. Support needs to look at this deal.',
  RELEASE_CONDITION_NOT_MET:
    'The work is approved, but this milestone still has a release condition outstanding, so payment has not gone out yet.',
  ESCROW_BLOCKED_BY_DISPUTE:
    'Payment is frozen while this deal is in dispute. The approval is recorded; the payout waits for the dispute to close.',
  INVALID_ESCROW_STATE:
    'Escrow for this milestone is not in a releasable state, so no payment went out. Check the Payments panel for this deal.',
  NOT_APPLICABLE: 'No payment is attempted on this action.',
};

/**
 * Human-readable explanation for a held payment. Unknown codes get an honest fallback naming the
 * code rather than a reassuring generic — a brand chasing an unpaid creator needs the identifier
 * to quote, and silently smoothing over a code we do not recognise is how this defect started.
 */
export function paymentHeldMessage(reason: string | null | undefined): string {
  if (!reason) {
    return 'The approval was recorded, but no payment was released. Check the Payments panel for this deal.';
  }
  return (
    REASONS[reason as PaymentHeldReason] ??
    `The approval was recorded, but no payment was released (${reason}). Check the Payments panel for this deal.`
  );
}

/**
 * Whether a held payment is something the brand can resolve themselves. Drives whether the
 * notice reads as an action or as a status — a dispute freeze is not a to-do.
 */
export function isBrandActionable(reason: string | null | undefined): boolean {
  return reason === 'MILESTONE_NOT_FUNDED' || reason === 'ESCROW_NOT_FOUND';
}
