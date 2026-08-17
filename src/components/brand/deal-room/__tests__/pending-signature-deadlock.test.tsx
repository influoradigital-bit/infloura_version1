/**
 * F-0250 follow-up — brand-side half of the deadlock-prevention property.
 *
 * `mapDealApiContractStatus` cannot resolve which party signed on a bare
 * `PENDING_SIGNATURES` + unfunded escrow, so it returns the ambiguous
 * `'pending_signature'` member (src/lib/creator-contract-mappers.ts). This spec proves the
 * property that actually prevents the deadlock on the BRAND side: in the 'pending_signature'
 * state, `DealContractTab`'s Sign control must stay reachable (it must not require
 * `status === 'generated'` exactly, the way the original bug did), and no copy may claim the
 * creator specifically has signed.
 *
 * See the companion creator-side spec:
 * src/components/creator/deal-room/__tests__/pending-signature-deadlock.test.tsx
 *
 * Run: npx vitest run src/components/brand/deal-room/__tests__/pending-signature-deadlock.test.tsx
 */

import { describe, it, expect, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { DealContractTab } from '../deal-contract-tab';
import type { ContractApiRecord } from '@/lib/api';

const mockContract: ContractApiRecord = {
  id: 'CTR-1',
  collaborationId: 'collab-1',
  status: 'PENDING_SIGNATURES',
  totalAmount: 10000,
  currency: 'INR',
  brandSignedAt: null,
  creatorSignedAt: '2026-08-17T10:00:00Z',
  milestones: [],
};

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    isApiLive: () => false,
    api: {
      ...actual.api,
      contracts: {
        ...actual.api.contracts,
        get: vi.fn(async () => mockContract),
      },
    },
  };
});

describe('DealContractTab — pending_signature keeps the brand Sign control reachable', () => {
  it('renders the signer-name input and Sign button on pending_signature', async () => {
    render(
      <DealContractTab
        dealId="deal-1"
        creatorName="Priya Sharma"
        campaignName="Summer Launch"
        dealValue={10000}
        contractId="CTR-1"
        status="pending_signature"
        onStatusChange={vi.fn()}
      />,
    );

    // Wait past the contract-terms fetch (F-0237) so the loading spinner clears.
    await waitFor(() =>
      expect(screen.getByLabelText(/type your full legal name to sign/i)).toBeInTheDocument(),
    );
    expect(screen.getByRole('button', { name: /sign & send to creator/i })).toBeInTheDocument();
  });

  it('does not claim the creator specifically has signed, and does not claim nobody has', async () => {
    render(
      <DealContractTab
        dealId="deal-1"
        creatorName="Priya Sharma"
        campaignName="Summer Launch"
        dealValue={10000}
        contractId="CTR-1"
        status="pending_signature"
        onStatusChange={vi.fn()}
      />,
    );

    await waitFor(() =>
      expect(screen.getByLabelText(/type your full legal name to sign/i)).toBeInTheDocument(),
    );

    // The old 'generated' guess asserted "Sent to creator for signature" — a false claim when
    // the creator has, in fact, already signed. That card is gated on status === 'brand_signed'
    // specifically and must not appear here either.
    expect(screen.queryByText(/sent to creator for signature/i)).not.toBeInTheDocument();
  });

  it('still shows "Sent to creator for signature" on the real brand_signed state (control)', async () => {
    render(
      <DealContractTab
        dealId="deal-1"
        creatorName="Priya Sharma"
        campaignName="Summer Launch"
        dealValue={10000}
        contractId="CTR-1"
        status="brand_signed"
        onStatusChange={vi.fn()}
      />,
    );

    expect(await screen.findByText(/sent to creator for signature/i)).toBeInTheDocument();
    expect(screen.queryByLabelText(/type your full legal name to sign/i)).not.toBeInTheDocument();
  });
});
