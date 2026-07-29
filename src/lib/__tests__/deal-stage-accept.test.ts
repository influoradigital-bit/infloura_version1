/**
 * CR-34 — the one mirror of `Collaboration.canAccept()`.
 *
 * Both deal rooms used to carry a private copy of this status list. The creator comment
 * deferred merging them to "when CR-07 wires the brand room up" (CR-07 shipped in Wave 2; the
 * lift never happened) and the brand comment claimed it was "tracked separately" (nothing
 * tracked it). Two copies of one backend precondition is the shape of CR-05, CR-13 and
 * CR-24 — when `canAccept()` moves and only one copy follows, the two sides of a single
 * negotiation disagree about whether an offer is live, which is CR-02 by another route.
 *
 * Run: npx vitest run src/lib/__tests__/deal-stage-accept.test.ts
 */

import { describe, it, expect } from 'vitest';
import type { CollaborationStatus } from '@/lib/types';
import {
  ACCEPTABLE_COLLABORATION_STATUSES,
  allowsProposalResponse,
  mapCollaborationStatusToDealStage,
} from '@/lib/deal-stage';

/**
 * The full partition of `CollaborationStatus`, mirroring `Collaboration.java:185-190`.
 *
 * Typed as `Record<CollaborationStatus, boolean>` on purpose: TypeScript requires EVERY member
 * of the union to be present, so adding a 14th status to `CollaborationStatus` breaks
 * `npm run typecheck` here until someone decides whether the server would accept an offer in
 * it. That is the exhaustiveness guard neither `allowsProposalResponse` (an `.includes()`) nor
 * `mapCollaborationStatusToDealStage` (has a `default:`) can provide on its own.
 */
const ACCEPTS_AN_OFFER: Record<CollaborationStatus, boolean> = {
  INVITED: true,
  APPLIED: true,
  SHORTLISTED: true,
  IN_NEGOTIATION: true,
  // Everything below has left the offer stage. TERMS_AGREED is the one people get wrong:
  // `doAccept` PRODUCES it, so an offer in it has already been accepted.
  TERMS_AGREED: false,
  CONTRACT_PENDING: false,
  CONTRACTED: false,
  IN_PROGRESS: false,
  REVIEW_PENDING: false,
  REVISION_REQUESTED: false,
  COMPLETED: false,
  CANCELLED: false,
  DISPUTED: false,
};

describe('allowsProposalResponse — the shared canAccept() mirror (CR-34)', () => {
  it.each(Object.entries(ACCEPTS_AN_OFFER))(
    'answers %s -> %s, matching Collaboration.canAccept()',
    (status, expected) => {
      expect(allowsProposalResponse(status as CollaborationStatus)).toBe(expected);
    },
  );

  it('exports exactly the four statuses canAccept() permits, and no more', () => {
    // Pinned as a set so a widening — the tempting way to "fix" a missing button — has to be
    // a deliberate edit here rather than a quiet one-line addition in a page.
    expect([...ACCEPTABLE_COLLABORATION_STATUSES].sort()).toEqual([
      'APPLIED',
      'INVITED',
      'IN_NEGOTIATION',
      'SHORTLISTED',
    ]);
  });

  it('refuses an unknown deal rather than assuming it is actionable', () => {
    // A room with no backend status behind it must not offer an action the server never
    // agreed to. Fail closed, not open.
    expect(allowsProposalResponse(undefined)).toBe(false);
    expect(allowsProposalResponse(null)).toBe(false);
  });

  it('is NOT the same question as the negotiating stage — the CR-27 trap', () => {
    // Both TERMS_AGREED and IN_NEGOTIATION render as "Negotiating", but only one can still
    // take an Accept. Gating a button on the stage instead of on this predicate is exactly
    // how CR-02 would come back on a third surface, and it is why CR-27 was closed WONTFIX.
    expect(mapCollaborationStatusToDealStage('TERMS_AGREED')).toBe('negotiating');
    expect(mapCollaborationStatusToDealStage('IN_NEGOTIATION')).toBe('negotiating');
    expect(allowsProposalResponse('TERMS_AGREED')).toBe(false);
    expect(allowsProposalResponse('IN_NEGOTIATION')).toBe(true);
  });
});
