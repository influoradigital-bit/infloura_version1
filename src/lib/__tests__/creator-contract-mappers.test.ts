/**
 * F-0250 — cross-surface-status-divergence in `mapDealApiContractStatus`.
 *
 * The backend reaches `ContractStatus.PENDING_SIGNATURES` after exactly ONE signature from
 * EITHER party, with no ordering enforced (`Contract#advanceIfFullySigned`,
 * influora-api/src/main/java/com/influora/domain/entity/Contract.java:148-154). Creators sign via
 * their own authenticated endpoint (`ContractService#recordSignatureForCreator`,
 * influora-api/src/main/java/com/influora/service/ContractService.java:576), so a creator-first
 * signature is a real, reachable path — not a hypothetical.
 *
 * `mapDealApiContractStatus` is only ever given the bare `ContractStatus` enum + `escrowFunded`
 * (never `brandSignedAt`/`creatorSignedAt`), so for `PENDING_SIGNATURES` it structurally cannot
 * know which party signed.
 *
 * Two wrong fixes have been tried and rejected:
 *   1. Guessing `'brand_signed'` unconditionally — correct for the brand-first path, but a lie
 *      on the creator-first path: `DealContractTab.canBrandSign` was `status === 'generated'`,
 *      so `'brand_signed'` hid the brand's Sign control whenever the creator had actually
 *      signed first. Deadlocked the brand.
 *   2. Guessing `'generated'` unconditionally — the opposite mistake. It hid the CREATOR's Sign
 *      control on the (more common) brand-first path: `CreatorDealContractTab.canSign` and
 *      `CreatorContractPanel.shouldShowSignButton` gate on `status === 'brand_signed'`, and
 *      `awaitingBrandSignature`/`creatorSigningStatus()` rendered "awaiting brand signature" —
 *      false, since the brand had already signed. Deadlocked the creator instead.
 *
 * The real fix is a 5th `DealContractStatus` member, `'pending_signature'`, meaning "exactly one
 * party has signed, which one is unknown." Every consumer treats it as "either party's Sign
 * control may still be live" and never labels a specific party as having signed while in this
 * state.
 *
 * The sibling `mapApiContractToDealStatus` is NOT part of this defect — it reads the real
 * `brandSignedAt`/`creatorSignedAt` timestamps and already handles both orderings correctly, and
 * must never return `'pending_signature'` since it always has enough information to resolve the
 * exact party. It's exercised here as a control group: it must keep behaving correctly across
 * brand-first, creator-first, both-signed and escrowFunded so this spec proves the coarse
 * function's guess, specifically, was the bug.
 *
 * Run: npx vitest run src/lib/__tests__/creator-contract-mappers.test.ts
 */

import { describe, it, expect } from 'vitest';
import {
  mapApiContractToDealStatus,
  mapDealApiContractStatus,
} from '@/lib/creator-contract-mappers';

describe('mapDealApiContractStatus (F-0250 — coarse status, signer unknown)', () => {
  it('does not claim the brand has signed on PENDING_SIGNATURES — the creator-first regression', () => {
    // THE original bug: the backend's PENDING_SIGNATURES is reachable via a creator-first
    // signature (ContractService#recordSignatureForCreator) just as much as a brand-first one,
    // and this function is never told which happened. Asserting 'brand_signed' here is the
    // mapper guessing an order it cannot know — and DealContractTab.canBrandSign === 'generated'
    // meant that guess hid the brand's Sign control on exactly the creator-first path,
    // deadlocking the deal.
    const result = mapDealApiContractStatus('PENDING_SIGNATURES', false);
    expect(result).not.toBe('brand_signed');
  });

  it('does not claim neither party has signed on PENDING_SIGNATURES — the brand-first regression', () => {
    // THE follow-up bug: returning 'generated' unconditionally is equally a guess — it asserts
    // zero signatures exist, which is false on the (common) brand-first path, and hides the
    // CREATOR's Sign control (CreatorDealContractTab.canSign / CreatorContractPanel
    // .shouldShowSignButton === 'brand_signed'). This is the case that fails against the
    // 'generated'-guessing code.
    const result = mapDealApiContractStatus('PENDING_SIGNATURES', false);
    expect(result).not.toBe('generated');
  });

  it('maps unfunded PENDING_SIGNATURES to the ambiguous pending_signature member, not a guess', () => {
    expect(mapDealApiContractStatus('PENDING_SIGNATURES', false)).toBe('pending_signature');
  });

  it('still reports active once escrow is funded, regardless of the signer-order ambiguity', () => {
    expect(mapDealApiContractStatus('PENDING_SIGNATURES', true)).toBe('active');
  });

  it('maps DRAFT to generated', () => {
    expect(mapDealApiContractStatus('DRAFT', false)).toBe('generated');
    expect(mapDealApiContractStatus('DRAFT', true)).toBe('generated');
  });

  it('maps ACTIVE/COMPLETED to creator_signed unless escrow is funded', () => {
    expect(mapDealApiContractStatus('ACTIVE', false)).toBe('creator_signed');
    expect(mapDealApiContractStatus('COMPLETED', false)).toBe('creator_signed');
    expect(mapDealApiContractStatus('ACTIVE', true)).toBe('active');
    expect(mapDealApiContractStatus('COMPLETED', true)).toBe('active');
  });

  it('returns undefined when there is no status at all', () => {
    expect(mapDealApiContractStatus(undefined, false)).toBeUndefined();
  });

  it('returns undefined for an unrecognized/terminated status rather than guessing', () => {
    expect(mapDealApiContractStatus('TERMINATED', false)).toBeUndefined();
  });
});

describe('mapApiContractToDealStatus (control group — already timestamp-driven, not buggy)', () => {
  it('brand-first: brandSignedAt set, creatorSignedAt null -> brand_signed', () => {
    const result = mapApiContractToDealStatus(
      { status: 'PENDING_SIGNATURES', brandSignedAt: '2026-08-17T10:00:00Z', creatorSignedAt: null },
      false,
    );
    expect(result).toBe('brand_signed');
  });

  it('creator-first: creatorSignedAt set, brandSignedAt null -> never brand_signed', () => {
    // The exact scenario the ticket names: creator signs first. The fine-grained mapper already
    // gets this right because it has the real timestamps — this is the control proving the bug
    // was specific to the coarse mapper's lack of information, not to signature-status mapping
    // in general.
    const result = mapApiContractToDealStatus(
      { status: 'PENDING_SIGNATURES', brandSignedAt: null, creatorSignedAt: '2026-08-17T10:00:00Z' },
      false,
    );
    expect(result).not.toBe('brand_signed');
    expect(result).toBe('generated');
  });

  it('both signed, escrow not funded -> creator_signed', () => {
    // status: 'ACTIVE' forces 'active' regardless of escrowFunded (see the ACTIVE||COMPLETED
    // branch below) — 'COMPLETED' is the case that actually exercises the escrowFunded ternary.
    const result = mapApiContractToDealStatus(
      {
        status: 'COMPLETED',
        brandSignedAt: '2026-08-17T10:00:00Z',
        creatorSignedAt: '2026-08-17T11:00:00Z',
      },
      false,
    );
    expect(result).toBe('creator_signed');
  });

  it('both signed, escrow funded -> active', () => {
    const result = mapApiContractToDealStatus(
      {
        status: 'ACTIVE',
        brandSignedAt: '2026-08-17T10:00:00Z',
        creatorSignedAt: '2026-08-17T11:00:00Z',
      },
      true,
    );
    expect(result).toBe('active');
  });

  it('neither signed -> generated', () => {
    const result = mapApiContractToDealStatus(
      { status: 'DRAFT', brandSignedAt: null, creatorSignedAt: null },
      false,
    );
    expect(result).toBe('generated');
  });
});
