/**
 * F-0223 — the brand must be told when an approval moved no money.
 *
 * WHY THIS FILE EXISTS
 * --------------------
 * Approving a deliverable is the act that pays the creator: BrandDeliverableService#approve
 * calls EscrowService#tryReleaseOnApproval in the same transaction. That method SKIPS the
 * release and returns normally on eight conditions — an unfunded milestone, an unmet release
 * condition, a dispute freeze — so the approval succeeds either way and used to return an
 * identical `{status: APPROVED}` whether the creator had just been paid or not.
 *
 * The mapping below is the whole user-facing half of the fix, so it is held here: every reason
 * code the server can emit must produce a message that says money did not move, and an
 * unrecognised code must NOT be smoothed over into something reassuring.
 *
 * Run: npx vitest run src/lib/__tests__/escrow-release-reason.test.ts
 */

import { describe, it, expect } from 'vitest';
import {
  paymentHeldMessage,
  isBrandActionable,
  type PaymentHeldReason,
} from '@/lib/escrow-release-reason';

/** Every code EscrowService.ReleaseOutcome can carry. Keep in step with isExpectedReleaseSkip. */
const ALL_REASONS: PaymentHeldReason[] = [
  'NO_MILESTONE',
  'MILESTONE_NOT_FOUND',
  'MILESTONE_NOT_FUNDED',
  'INVALID_ESCROW_STATE',
  'RELEASE_CONDITION_NOT_MET',
  'ESCROW_BLOCKED_BY_DISPUTE',
  'ESCROW_NOT_FOUND',
  'COLLABORATION_NOT_FOUND',
  'NOT_APPLICABLE',
];

describe('F-0223 — every held reason explains that no money moved', () => {
  it.each(ALL_REASONS)('%s produces a non-empty, specific message', (reason) => {
    const msg = paymentHeldMessage(reason);
    expect(msg.length).toBeGreaterThan(20);
    // The bug was silence dressed as success, so no mapping may make an AFFIRMATIVE release
    // claim. The lookbehind matters: "so no payment was released" is exactly the sentence these
    // messages should contain, and a bare /payment was released/ would reject the correct copy
    // along with the wrong copy.
    expect(msg).not.toMatch(/(?<!no )payment (has been |was )?released/i);
  });

  it('never returns the raw enum as the whole message', () => {
    for (const reason of ALL_REASONS) {
      expect(paymentHeldMessage(reason)).not.toBe(reason);
    }
  });

  it('tells the brand what to do about the two they can fix themselves', () => {
    expect(paymentHeldMessage('MILESTONE_NOT_FUNDED')).toMatch(/fund it from the deal room/i);
    expect(paymentHeldMessage('ESCROW_NOT_FOUND')).toMatch(/fund it from the deal room/i);
  });

  it('does not present a dispute freeze as a brand to-do', () => {
    expect(isBrandActionable('ESCROW_BLOCKED_BY_DISPUTE')).toBe(false);
    expect(paymentHeldMessage('ESCROW_BLOCKED_BY_DISPUTE')).toMatch(/dispute/i);
  });

  it('flags exactly the two self-serviceable reasons as actionable', () => {
    const actionable = ALL_REASONS.filter(isBrandActionable);
    expect(actionable).toEqual(['MILESTONE_NOT_FUNDED', 'ESCROW_NOT_FOUND']);
  });

  it('an UNRECOGNISED code still says no payment went out, and quotes the code', () => {
    // A brand chasing an unpaid creator needs the identifier to quote. Smoothing an unknown
    // code into a generic reassurance is how this defect started.
    const msg = paymentHeldMessage('SOME_NEW_SERVER_CODE');
    expect(msg).toMatch(/no payment was released/i);
    expect(msg).toContain('SOME_NEW_SERVER_CODE');
  });

  it('a null/undefined reason still refuses to imply payment', () => {
    for (const empty of [null, undefined, '']) {
      expect(paymentHeldMessage(empty)).toMatch(/no payment was released/i);
    }
  });
});
