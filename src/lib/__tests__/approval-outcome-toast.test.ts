import { describe, expect, it } from 'vitest';
import { approvalOutcomeToast, isBrandActionable, paymentHeldMessage } from '../escrow-release-reason';

/**
 * Deliverables are never linked to a payment milestone, so the release attempt at approval
 * answers NO_MILESTONE every time. Every surface used to render that as a red "Approved — but
 * payment was NOT released ... needs a contract with milestones" — on deals with a signed contract
 * and funded milestones, where the truth is "approved; now press Release".
 */
describe('approvalOutcomeToast', () => {
  it('a real release is plain success', () => {
    expect(approvalOutcomeToast({ paymentReleased: true })).toEqual({
      title: 'Deliverable approved',
      description: 'Payment has been released to the creator.',
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
