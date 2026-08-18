/**
 * F-0272 (demo-sign-flow-dead) — `api.contracts.get` resolved `null` in mock mode. Once
 * DealContractTab started gating its Sign control on a real fetched record (F-0237), that null
 * disabled signing in EVERY demo walkthrough: the panel rendered "Contract terms are not
 * available" where the contract should be, and the demo sign flow was dead end to end.
 *
 * The fix (src/lib/api.ts `contracts.get`) gives mock mode a real fixture, reachable only
 * through the same `isLive()` guard every other api.ts mock already uses — nothing changed in
 * live mode, which still gets an honest "unavailable" state on a failed/absent contract (that
 * guarantee is F-0237/F-0238's, and must not regress here).
 *
 * Two directions, both required by this record:
 *   1. Demo mode: the walkthrough renders a real contract and can reach the Sign control.
 *      Exercises the ACTUAL api.ts mock branch (not a stand-in) so this test fails if the real
 *      fixture wiring regresses, not just a test double.
 *   2. Live mode with a failing/absent contract: still shows the honest unavailable state, and
 *      never a fabricated one — proves the mock fixture cannot leak into the live-mode render.
 *
 * Run: npx vitest run src/components/brand/deal-room/__tests__/demo-sign-flow-alive.test.tsx
 */

import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

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

describe('F-0272 — demo mode: mock contracts.get resolves a real fixture (not null)', () => {
  it('renders the mock fixture milestones instead of "Contract terms are not available"', async () => {
    vi.doMock('@/lib/api', async () => {
      const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
      // isApiLive: false — the actual mock branch (unmodified) is what this test exercises.
      return { ...actual, isApiLive: () => false };
    });
    const { DealContractTab: MockedDealContractTab } = await import('../deal-contract-tab');

    render(<MockedDealContractTab {...baseProps} />);

    // The old bug rendered this permanently. It must never appear once the fixture resolves.
    await waitFor(() =>
      expect(screen.queryByText(/loading contract terms/i)).not.toBeInTheDocument(),
    );
    expect(screen.queryByText(/contract terms are not available/i)).not.toBeInTheDocument();

    // The real api.ts mock fixture's milestone descriptions (src/lib/api.ts contracts.get).
    expect(screen.getByText(/on signing/i)).toBeInTheDocument();
    expect(screen.getByText(/on deliverable approval/i)).toBeInTheDocument();
  });

  it('can reach and use the Sign control end to end (the demo sign flow is not dead)', async () => {
    vi.doMock('@/lib/api', async () => {
      const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
      return { ...actual, isApiLive: () => false };
    });
    const { DealContractTab: MockedDealContractTab } = await import('../deal-contract-tab');

    render(<MockedDealContractTab {...baseProps} />);

    const nameInput = await screen.findByLabelText(/type your full legal name to sign/i);
    const signButton = screen.getByRole('button', { name: /sign & send to creator/i });

    // F-0237: disabled until a real contract record is loaded AND a name is typed.
    expect(signButton).toBeDisabled();

    const user = userEvent.setup();
    await user.type(nameInput, 'Founder Name');

    await waitFor(() => expect(signButton).not.toBeDisabled());
  });
});

describe('F-0272 control — live mode with a failing/absent contract stays honest, never fabricated', () => {
  it('shows the unavailable state and keeps Sign disabled when the live fetch fails', async () => {
    vi.doMock('@/lib/api', async () => {
      const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
      return {
        ...actual,
        isApiLive: () => true,
        api: {
          ...actual.api,
          contracts: {
            ...actual.api.contracts,
            get: vi.fn(async () => {
              throw new actual.ApiError('NOT_FOUND', 'Contract not found', 404);
            }),
          },
        },
      };
    });
    const { DealContractTab: MockedDealContractTab } = await import('../deal-contract-tab');

    render(<MockedDealContractTab {...baseProps} />);

    expect(await screen.findByText(/contract not found/i)).toBeInTheDocument();

    // Never the demo fixture's milestone text, and never a fabricated substitute.
    expect(screen.queryByText(/on signing/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/on deliverable approval/i)).not.toBeInTheDocument();

    const signButton = screen.getByRole('button', { name: /sign & send to creator/i });
    expect(signButton).toBeDisabled();
  });

  it('shows the unavailable state (not the mock fixture) when the live contract resolves null', async () => {
    vi.doMock('@/lib/api', async () => {
      const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
      return {
        ...actual,
        isApiLive: () => true,
        api: {
          ...actual.api,
          contracts: {
            ...actual.api.contracts,
            get: vi.fn(async () => null),
          },
        },
      };
    });
    const { DealContractTab: MockedDealContractTab } = await import('../deal-contract-tab');

    render(<MockedDealContractTab {...baseProps} />);

    expect(await screen.findByText(/contract terms are not available yet/i)).toBeInTheDocument();
    expect(screen.queryByText(/on signing/i)).not.toBeInTheDocument();

    const signButton = screen.getByRole('button', { name: /sign & send to creator/i });
    expect(signButton).toBeDisabled();
  });
});
