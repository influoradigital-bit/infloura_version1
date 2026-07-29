/**
 * CR-30 — the brand pipeline board's columns.
 *
 * This mapping was a private switch inside `brand-pipeline.tsx` and it held the LAST copy of
 * `TERMS_AGREED -> CONTRACTED`, the mapping CR-05 deleted from `creator-chat.tsx` and CR-24
 * deleted from `brand-chat.tsx`. The observable result: one deal read "Negotiating" in the
 * brand deal room while sitting under **Contracted** on this board — same brand, same session,
 * with no contract in existence. Being private and untested is what let it survive three
 * rounds of fixing the identical bug elsewhere.
 *
 * Run: npx vitest run src/lib/__tests__/brand-pipeline-stage.test.ts
 */

import { describe, it, expect } from 'vitest';
import type { CollaborationStatus } from '@/lib/types';
import {
  mapCollaborationStatusToPipelineStage,
  type BrandPipelineStage,
} from '@/lib/brand-pipeline-stage';
import { mapCollaborationStatusToDealStage } from '@/lib/deal-stage';

/**
 * The full partition. Typed `Record<CollaborationStatus, ...>` so a 14th status breaks
 * `npm run typecheck` here until someone decides which column it belongs in — the same
 * exhaustiveness guard used for the stage mapper and the canAccept mirror.
 */
const COLUMN_BY_STATUS: Record<CollaborationStatus, BrandPipelineStage | null> = {
  // The OUTREACH delta: inbound interest nobody has opened terms on yet.
  INVITED: 'OUTREACH',
  APPLIED: 'OUTREACH',
  SHORTLISTED: 'OUTREACH',
  // Genuine negotiation. TERMS_AGREED is here, NOT under Contracted — see the test below.
  IN_NEGOTIATION: 'NEGOTIATING',
  TERMS_AGREED: 'NEGOTIATING',
  CONTRACT_PENDING: 'CONTRACTED',
  CONTRACTED: 'CONTRACTED',
  IN_PROGRESS: 'IN_PROGRESS',
  REVIEW_PENDING: 'REVIEW',
  REVISION_REQUESTED: 'REVIEW',
  COMPLETED: 'SETTLED',
  // No honest column on a 6-stage board; the caller filters these rows out.
  CANCELLED: null,
  DISPUTED: null,
};

describe('brand pipeline columns (CR-30)', () => {
  it.each(Object.entries(COLUMN_BY_STATUS))('places %s in %s', (status, expected) => {
    expect(mapCollaborationStatusToPipelineStage(status as CollaborationStatus)).toBe(expected);
  });

  it('never files TERMS_AGREED under Contracted — there is no contract yet', () => {
    // The actual CR-30 defect, stated as intent rather than as a table row. `doAccept`
    // PRODUCES TERMS_AGREED and no contract row exists at that moment, so a Contracted column
    // containing it is the board asserting something untrue.
    expect(mapCollaborationStatusToPipelineStage('TERMS_AGREED')).not.toBe('CONTRACTED');
    expect(mapCollaborationStatusToPipelineStage('TERMS_AGREED')).toBe('NEGOTIATING');
  });

  it('agrees with the brand deal room about TERMS_AGREED, which is the whole point', () => {
    // brand-chat.tsx derives its label from the shared DealStage (CR-24). Before CR-30 this
    // board disagreed with it on exactly this status. Both must now read pre-contract.
    expect(mapCollaborationStatusToDealStage('TERMS_AGREED')).toBe('negotiating');
    expect(mapCollaborationStatusToPipelineStage('TERMS_AGREED')).toBe('NEGOTIATING');
  });

  it('keeps OUTREACH distinct from NEGOTIATING — the one deliberate delta from DealStage', () => {
    // DealStage folds all four of these into 'negotiating'. The board splits them, and the
    // Wave 6 ruling kept that split. If someone "simplifies" this by deriving the column
    // straight from DealStage, these four collapse into one and deals move columns silently.
    for (const s of ['APPLIED', 'SHORTLISTED'] as const) {
      expect(mapCollaborationStatusToDealStage(s)).toBe('negotiating');
      expect(mapCollaborationStatusToPipelineStage(s)).toBe('OUTREACH');
    }
    for (const s of ['IN_NEGOTIATION', 'TERMS_AGREED'] as const) {
      expect(mapCollaborationStatusToDealStage(s)).toBe('negotiating');
      expect(mapCollaborationStatusToPipelineStage(s)).toBe('NEGOTIATING');
    }
  });

  it('declines disputed/cancelled rather than dropping them into a default column', () => {
    expect(mapCollaborationStatusToPipelineStage('CANCELLED')).toBeNull();
    expect(mapCollaborationStatusToPipelineStage('DISPUTED')).toBeNull();
  });
});
