/**
 * Contract status label agreement — F-0321 (cross-surface-label-contradiction).
 *
 * F-0252 mapped the backend's real terminal `ContractStatus`, `CANCELLED`, to two different UI
 * labels in two different brand surfaces: `contracts-and-deliverables.tsx`'s status badge said
 * "Expired" (`mapApiContractStatus` -> `'expired'` -> `statusConfig.expired.label`) while
 * `brand-campaign-detail.tsx`'s `contractStatusLabel` said "Contract cancelled" for the exact
 * same backend value. A contract cancelled by a party is not one whose expiration date passed —
 * the two surfaces told the brand two different, contradictory stories about the same real
 * state (not a regression: pre-fix it fell through to the equally-wrong "Draft").
 *
 * This is a real agreement test, not a snapshot of today's strings: it derives EACH surface's
 * label through its OWN real production code path for the same backend `ContractStatus` and
 * checks they agree on the underlying fact (cancelled, not expired) rather than pinning one
 * literal both happen to share today.
 *
 * Run: npx vitest run src/pages/__tests__/contract-status-label-agreement.test.ts
 */

import { describe, it, expect } from 'vitest';
import { contractStatusBadgeLabel } from '@/components/brand/contracts/contracts-and-deliverables';
import { contractStatusLabel } from '../brand-campaign-detail';
import type { ContractStatus } from '@/lib/types';

/** The real backend enum (influora-api ContractStatus.java / src/lib/types.ts). */
const REAL_BACKEND_STATUSES: ContractStatus[] = [
  'DRAFT',
  'PENDING_SIGNATURES',
  'ACTIVE',
  'COMPLETED',
  'CANCELLED',
];

describe('F-0321 — contracts-and-deliverables.tsx and brand-campaign-detail.tsx agree on CANCELLED', () => {
  it('neither surface calls a cancelled contract "expired"', () => {
    const badgeLabel = contractStatusBadgeLabel('CANCELLED');
    const actionLabel = contractStatusLabel('CANCELLED');

    expect(
      badgeLabel.toLowerCase(),
      `contracts-and-deliverables.tsx's badge label for CANCELLED: "${badgeLabel}"`,
    ).not.toContain('expir');
    expect(
      actionLabel.toLowerCase(),
      `brand-campaign-detail.tsx's label for CANCELLED: "${actionLabel}"`,
    ).not.toContain('expir');
  });

  it('both surfaces describe CANCELLED as cancelled, in agreement with each other', () => {
    const badgeLabel = contractStatusBadgeLabel('CANCELLED');
    const actionLabel = contractStatusLabel('CANCELLED');

    expect(
      badgeLabel.toLowerCase(),
      `contracts-and-deliverables.tsx's badge label for CANCELLED: "${badgeLabel}"`,
    ).toContain('cancel');
    expect(
      actionLabel.toLowerCase(),
      `brand-campaign-detail.tsx's label for CANCELLED: "${actionLabel}"`,
    ).toContain('cancel');
  });

  it('every other real backend status still gets a real, non-empty label from both surfaces (no accidental collapse)', () => {
    for (const status of REAL_BACKEND_STATUSES) {
      if (status === 'CANCELLED') continue;
      const badgeLabel = contractStatusBadgeLabel(status);
      const actionLabel = contractStatusLabel(status);
      expect(badgeLabel.length, `contracts-and-deliverables.tsx gave no label for ${status}`).toBeGreaterThan(0);
      expect(actionLabel.length, `brand-campaign-detail.tsx gave no label for ${status}`).toBeGreaterThan(0);
    }
  });
});
