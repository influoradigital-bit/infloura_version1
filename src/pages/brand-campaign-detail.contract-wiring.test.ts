/**
 * Campaign detail — contract wiring.
 *
 * The brand had no route from a campaign to its contracts: the collaboration timeline cannot
 * show them (the backend writes no `contract` deal-message kind — collaboration-timeline.tsx:253),
 * and the collaborator cards dropped `contractId`/`contractStatus` in the view-model mapper even
 * though `DealResponse` carries both (DealService.java:1342-1343).
 *
 * That is the same failure shape as the React #31 crash fixed in 0fa531c: real data present on
 * the wire, lost between the response and the render. These tests pin the mapper so the fields
 * cannot be silently dropped again.
 *
 * Run: npx vitest run src/pages/brand-campaign-detail.contract-wiring.test.ts
 */

import { describe, it, expect } from 'vitest';
import { dealToActiveCollaboratorView, contractStatusLabel } from './brand-campaign-detail';
import type { Deal } from '@/lib/api';

function makeDeal(overrides: Partial<Deal> = {}): Deal {
  return {
    id: 'deal-1',
    campaignId: 'camp-1',
    campaignName: 'Summer Launch',
    counterpartyId: 'cr-1',
    counterpartyName: 'Priya Sharma',
    status: 'TERMS_AGREED',
    dealValue: 25000,
    currency: 'INR',
    unreadCount: 0,
    deliverablesDone: 0,
    deliverablesTotal: 4,
    ...overrides,
  } as Deal;
}

describe('dealToActiveCollaboratorView — contract fields survive the mapper', () => {
  it('carries contractId, contractStatus, rawStatus and dealValue through', () => {
    const view = dealToActiveCollaboratorView(
      makeDeal({ contractId: 'CTR_9', contractStatus: 'PENDING_SIGNATURES', status: 'CONTRACTED' }),
    );

    expect(view.contractId).toBe('CTR_9');
    expect(view.contractStatus).toBe('PENDING_SIGNATURES');
    // rawStatus must be the unbucketed collaboration status — the generate gate keys off
    // TERMS_AGREED exactly, so the coarse display bucket is not a substitute.
    expect(view.rawStatus).toBe('CONTRACTED');
    expect(view.dealValue).toBe(25000);
  });

  it('leaves the contract fields absent — not zeroed or faked — when no contract exists', () => {
    const view = dealToActiveCollaboratorView(makeDeal({ status: 'TERMS_AGREED' }));

    expect(view.contractId).toBeUndefined();
    expect(view.contractStatus).toBeUndefined();
    // Still exposes the gate + prefill the "Generate contract" path needs.
    expect(view.rawStatus).toBe('TERMS_AGREED');
    expect(view.dealValue).toBe(25000);
  });

  it('does not disturb the fields the card already rendered', () => {
    const view = dealToActiveCollaboratorView(
      makeDeal({ counterpartyHandle: '@priya', deliverablesDone: 2, deliverablesTotal: 5 }),
    );

    expect(view.creator.name).toBe('Priya Sharma');
    expect(view.creator.username).toBe('@priya');
    expect(view.deliverables).toEqual({ completed: 2, total: 5 });
  });
});

describe('contractStatusLabel', () => {
  it('names the next action for each real ContractStatus', () => {
    expect(contractStatusLabel('DRAFT')).toBe('Contract: draft');
    expect(contractStatusLabel('PENDING_SIGNATURES')).toBe('Sign contract');
    expect(contractStatusLabel('ACTIVE')).toBe('Contract active');
    expect(contractStatusLabel('COMPLETED')).toBe('Contract complete');
    expect(contractStatusLabel('TERMINATED')).toBe('Contract ended');
    expect(contractStatusLabel('DISPUTED')).toBe('Contract disputed');
  });

  it('still offers the link when status is unknown — a contractId means a real contract', () => {
    // The row only renders this label when contractId exists, so "no contract" is never the
    // honest reading of a missing status.
    expect(contractStatusLabel(undefined)).toBe('View contract');
  });
});
