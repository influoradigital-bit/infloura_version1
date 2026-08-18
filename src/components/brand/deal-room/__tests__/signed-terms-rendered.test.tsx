/**
 * F-0271 (signed-terms-not-contract-repair) — behavioral proof that the "Terms (from contract,
 * read-only)" panel actually RENDERS `contractRecord.terms` when present, and states plainly
 * when absent, rather than a static grep merely confirming the string `contractRecord.terms`
 * appears somewhere in the file (which would also pass if the read were dead code).
 *
 * F-0237 is the regression class this guards against: a hardcoded clause list shown regardless
 * of what the contract actually said. F-0283 gave the client a real `terms` field
 * (`ContractApiRecord.terms`, sourced from `ContractResponse.terms`); this spec proves the panel
 * actually uses it in both directions — present and absent — not just that the code compiles.
 *
 * Run: npx vitest run src/components/brand/deal-room/__tests__/signed-terms-rendered.test.tsx
 */

import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';

afterEach(() => {
  vi.restoreAllMocks();
  vi.resetModules();
});

const baseProps = {
  dealId: 'deal-1',
  creatorName: 'Priya Sharma',
  campaignName: 'Summer Launch',
  dealValue: 50000,
  contractId: 'CTR-1',
  status: 'generated' as const,
  onStatusChange: vi.fn(),
};

const REAL_TERMS =
  'Creator grants Brand a 90-day usage license for the delivered Reels across owned social channels.';

describe('F-0271 — the Terms panel renders the REAL contractRecord.terms value', () => {
  it('shows the actual captured terms text when the contract has terms on file', async () => {
    vi.doMock('@/lib/api', async () => {
      const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
      return {
        ...actual,
        isApiLive: () => true,
        api: {
          ...actual.api,
          contracts: {
            ...actual.api.contracts,
            get: vi.fn(async () => ({
              id: 'CTR-1',
              collaborationId: 'collab-1',
              status: 'GENERATED',
              totalAmount: 50000,
              currency: 'INR',
              brandSignedAt: null,
              creatorSignedAt: null,
              terms: REAL_TERMS,
              milestones: [],
            })),
          },
        },
      };
    });
    const { DealContractTab: MockedDealContractTab } = await import('../deal-contract-tab');

    render(<MockedDealContractTab {...baseProps} />);

    expect(await screen.findByText(REAL_TERMS)).toBeInTheDocument();
    // Never the honest "absent" copy, and never F-0237's fabricated clause list.
    expect(screen.queryByText(/no terms are on file/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/usage rights for 6 months/i)).not.toBeInTheDocument();
  });

  it('states plainly that no terms are on file when the real contract has none, without inventing filler', async () => {
    vi.doMock('@/lib/api', async () => {
      const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
      return {
        ...actual,
        isApiLive: () => true,
        api: {
          ...actual.api,
          contracts: {
            ...actual.api.contracts,
            get: vi.fn(async () => ({
              id: 'CTR-1',
              collaborationId: 'collab-1',
              status: 'GENERATED',
              totalAmount: 50000,
              currency: 'INR',
              brandSignedAt: null,
              creatorSignedAt: null,
              terms: null,
              milestones: [],
            })),
          },
        },
      };
    });
    const { DealContractTab: MockedDealContractTab } = await import('../deal-contract-tab');

    render(<MockedDealContractTab {...baseProps} />);

    await waitFor(() =>
      expect(screen.queryByText(/loading contract terms/i)).not.toBeInTheDocument(),
    );

    // Honest "no terms on file" — a real record loaded fine, it simply has none.
    expect(await screen.findByText(/no terms are on file for this contract/i)).toBeInTheDocument();
    // Never the generic "unavailable" copy (that's for a MISSING record, not an empty field).
    expect(screen.queryByText(/contract terms are not available yet/i)).not.toBeInTheDocument();
    // Never F-0237's fabricated 5-item clause list.
    expect(screen.queryByText(/usage rights for 6 months/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/2 revision rounds/i)).not.toBeInTheDocument();

    // A contract with no terms and no milestones can still be signed — F-0271 must not disable
    // signing over an honestly-empty terms field the way it correctly does over a MISSING record.
    const nameInput = screen.getByLabelText(/type your full legal name to sign/i);
    expect(nameInput).not.toBeDisabled();
  });
});
