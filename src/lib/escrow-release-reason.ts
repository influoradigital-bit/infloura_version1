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
  | 'MANUAL_RELEASE_REQUIRED'
  | 'ALREADY_RELEASED'
  | 'PAYMENT_REFUNDED'
  | 'NOT_APPLICABLE';

const REASONS: Record<PaymentHeldReason, string> = {
  // The F-0222 signature: funded campaign-level, so the hold never reached the milestone.
  MILESTONE_NOT_FUNDED:
    'Funds were never secured for this milestone, so nothing was paid out. Secure them from the deal room and the payment will release.',
  ESCROW_NOT_FOUND:
    'No secured funds exist for this milestone, so nothing was paid out. Secure them from the deal room and the payment will release.',
  NO_MILESTONE:
    'This deliverable is not linked to a payment milestone, so no payment was released. It needs a contract with milestones before it can pay out.',
  MILESTONE_NOT_FOUND:
    'The payment milestone behind this deliverable could not be found, so nothing was paid out. Support needs to look at this deal.',
  COLLABORATION_NOT_FOUND:
    'The deal behind this deliverable could not be found, so nothing was paid out. Support needs to look at this deal.',
  // paytrigger — approving a draft is not the payment trigger. The creator is paid after their
  // post is live and they have submitted the link (EscrowService#assertReleaseConditionSatisfied,
  // release condition ON_POSTED). The old copy — "this milestone still has a release condition
  // outstanding" — named the column, not the rule, and left the brand nothing to do next.
  RELEASE_CONDITION_NOT_MET:
    "The draft is approved, but payment goes out after the post is live. Once the creator posts and submits the link, release the payment from this deal's Payments panel.",
  // Deliberately ranked ABOVE the funded case in BrandDeliverableService#describeUnlinkedHold: a
  // release is refused outright while a dispute is open (EscrowService#assertEscrowNotBlockedByDispute),
  // so pointing the brand at the Release button here would send them to a call that throws.
  ESCROW_BLOCKED_BY_DISPUTE:
    'Payment is frozen while this deal is in dispute — the secured funds stay where they are. The approval is recorded; the payout waits for the dispute to close.',
  INVALID_ESCROW_STATE:
    'The secured funds for this milestone are not in a releasable state, so no payment went out. Check the Payments panel for this deal.',
  // Approval does not move money on its own: the funds for this deal are secured and waiting,
  // and the Release action in the Payments panel is what pays them out.
  // Role-neutral on purpose: any workspace member can approve a deliverable, but releasing
  // secured funds is Owner/Admin only, so "release them" would send a Manager to a control they
  // cannot use.
  //
  // paytrigger — this is the code a brand actually gets when they approve a draft on a funded
  // deal (BrandDeliverableService#describeUnlinkedHold), so it is the one sentence that sets
  // their expectation of when payment happens. It used to read as "approved, now go press
  // Release", which since the release gate was switched on sends them straight into a refusal:
  // markPosted requires APPROVED, so at the moment of approval the post is never live yet. It
  // now names the actual next event, and whose move it is.
  MANUAL_RELEASE_REQUIRED:
    "The work is approved and the funds stay secured. Payment goes out after the post is live — once the creator posts and submits the link, a workspace Owner or Admin releases it from this deal's Payments panel.",
  PAYMENT_REFUNDED:
    'The work is approved, but secured funds for this deal were refunded to your wallet, so nothing was paid out. Check the Payments panel for this deal.',
  ALREADY_RELEASED: 'The payment for this deal has already been released to the creator.',
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
  return (
    reason === 'MILESTONE_NOT_FUNDED' || reason === 'ESCROW_NOT_FOUND' || reason === 'MANUAL_RELEASE_REQUIRED'
  );
}

/**
 * The toast for a deliverable approval, shared by every surface that can approve one, so they
 * cannot drift apart again.
 *
 * Every approval used to come back `NO_MILESTONE` (deliverables are never linked to a milestone),
 * and each surface showed it as a red "Approved — but payment was NOT released ... needs a contract
 * with milestones", including on deals with a signed contract and funded milestones. The server
 * now reports the real state; this renders it with the right weight: an approval that simply
 * leaves the brand one click from paying is NOT an error, and one where everything is already
 * paid is plain success.
 */
export function approvalOutcomeToast(result: {
  paymentReleased: boolean;
  paymentHeldReason?: string | null;
}): { title: string; description: string; variant?: 'destructive' } {
  if (result.paymentReleased) {
    return { title: 'Deliverable approved', description: 'The post is already live, so the payment has gone to the creator.' };
  }
  const reason = result.paymentHeldReason;
  if (reason === 'MANUAL_RELEASE_REQUIRED') {
    return { title: 'Deliverable approved — payment is ready to release', description: paymentHeldMessage(reason) };
  }
  if (reason === 'ALREADY_RELEASED') {
    return { title: 'Deliverable approved', description: paymentHeldMessage(reason) };
  }
  // paytrigger — this one is NOT an error, and must not be styled as one. The payment trigger is
  // the live post, so an approval that does not pay is the designed, expected outcome of approving
  // a draft: the release gate (EscrowService#assertReleaseConditionSatisfied) refuses until every
  // deliverable is POSTED. Before the gate was switched on this branch was unreachable in
  // practice; with it on it is the common case, and leaving it in the red "payment was NOT
  // released" bucket would report correct behaviour as a failure on every single approval.
  if (reason === 'RELEASE_CONDITION_NOT_MET') {
    return {
      title: 'Draft approved — payment follows the live post',
      description: paymentHeldMessage(reason),
    };
  }
  return {
    title: 'Approved — but payment was NOT released',
    description: paymentHeldMessage(reason),
    variant: 'destructive',
  };
}
