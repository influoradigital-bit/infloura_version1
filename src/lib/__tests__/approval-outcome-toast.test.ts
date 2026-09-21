import { describe, expect, it } from 'vitest';
import { approvalOutcomeToast, isBrandActionable, paymentHeldMessage } from '../escrow-release-reason';

/**
 * Deliverables are never linked to a payment milestone, so the release attempt at approval
 * answers NO_MILESTONE every time. Every surface used to render that as a red "Approved — but
 * payment was NOT released ... needs a contract with milestones" — on deals with a signed contract
 * and funded milestones, where the truth is "approved; now press Release".
 */
describe('approvalOutcomeToast', () => {
  // paytrigger round 2 — the description names WHY the money moved. `paymentReleased: true` can
  // only happen once the deliverable is POSTED (EscrowService#assertReleaseConditionSatisfied),
  // so 'Deliverable approved' beside a bare 'Payment has been released' read as approval being
  // the thing that paid — the exact confusion this wave exists to remove.
  it('a real release is plain success, and says the live post is why', () => {
    expect(approvalOutcomeToast({ paymentReleased: true })).toEqual({
      title: 'Deliverable approved',
      description: 'The post is already live, so the payment has gone to the creator.',
    });
  });

  it('funded and waiting: NOT an error, and it says where the release lives', () => {
    const toast = approvalOutcomeToast({ paymentReleased: false, paymentHeldReason: 'MANUAL_RELEASE_REQUIRED' });
    expect(toast.variant).toBeUndefined();
    expect(toast.title).toMatch(/ready to release/i);
    expect(toast.description).toMatch(/Payments panel/);
    expect(toast.description).not.toMatch(/needs a contract/i);
    expect(isBrandActionable('MANUAL_RELEASE_REQUIRED')).toBe(true);
  });

  // paytrigger — the release-condition gate is now ON by default, and the payment trigger is the
  // live post. So the expected outcome of approving a DRAFT is "approved, nobody paid yet". If
  // that stayed in the red bucket, every correct approval in the product would be reported to the
  // brand as a failure.
  it('approved but not yet posted: the designed outcome, not an error', () => {
    const toast = approvalOutcomeToast({
      paymentReleased: false,
      paymentHeldReason: 'RELEASE_CONDITION_NOT_MET',
    });
    expect(toast.variant).toBeUndefined();
    expect(toast.title).toMatch(/live post/i);
    expect(toast.description).toMatch(/after the post is live/i);
    // The brand is not the one who unblocks this — the creator is.
    expect(isBrandActionable('RELEASE_CONDITION_NOT_MET')).toBe(false);
  });

  it('already paid out: plain success, not a warning', () => {
    const toast = approvalOutcomeToast({ paymentReleased: false, paymentHeldReason: 'ALREADY_RELEASED' });
    expect(toast.variant).toBeUndefined();
    expect(toast.title).toBe('Deliverable approved');
  });

  it('genuinely unpaid states stay loud', () => {
    for (const reason of ['MILESTONE_NOT_FUNDED', 'NO_MILESTONE', 'ESCROW_BLOCKED_BY_DISPUTE', 'SOMETHING_NEW']) {
      const toast = approvalOutcomeToast({ paymentReleased: false, paymentHeldReason: reason });
      expect(toast.variant, reason).toBe('destructive');
      expect(toast.description, reason).toBe(paymentHeldMessage(reason));
    }
    expect(approvalOutcomeToast({ paymentReleased: false }).variant).toBe('destructive');
  });
});
