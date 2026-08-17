/**
 * F-0258 (client-invented-fee-split) — brand-campaign-detail.tsx.
 *
 * The sidebar "Budget Breakdown" card computed "Creator Pay (est.)" / "Platform Fee (10%)" /
 * "GST (18% on fee)" / "Contingency" as `budget.max * 0.82 / 0.10 / 0.018 / 0.062` — four
 * numbers nobody measured, rendered as money under a Lock icon that implied the funds were
 * already apportioned/escrowed. No endpoint computes that split. The only real fee schedule
 * this app exposes to a brand is GET /brand/platform-fee (wired at api.wallet.brandPlatformFee),
 * which returns a single feeBps/feePercent — the fix wires that real number in and drops
 * everything that isn't backed by it.
 *
 * Run: npx vitest run src/pages/__tests__/brand-campaign-detail.fee-block.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

const toastMock = vi.fn();
vi.mock('@/hooks/use-toast', () => ({
  useToast: () => ({ toast: (...a: unknown[]) => toastMock(...a) }),
  toast: (...a: unknown[]) => toastMock(...a),
}));

const navigateMock = vi.fn();
vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom');
  return {
    ...actual,
    useNavigate: () => navigateMock,
    useParams: () => ({ id: 'camp-1' }),
  };
});

const campaignsGet = vi.fn();
const dealsList = vi.fn();
const brandPlatformFee = vi.fn();

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    isApiLive: () => true,
    api: {
      ...actual.api,
      campaigns: {
        ...actual.api.campaigns,
        get: (...a: unknown[]) => campaignsGet(...a),
        analytics: vi.fn().mockResolvedValue(null),
      },
      deals: { ...actual.api.deals, list: (...a: unknown[]) => dealsList(...a) },
      wallet: {
        ...actual.api.wallet,
        brandPlatformFee: (...a: unknown[]) => brandPlatformFee(...a),
      },
    },
  };
});

import BrandCampaignDetailPage from '../brand-campaign-detail';

const campaign = {
  id: 'camp-1',
  workspaceId: 'ws-1',
  title: 'Summer Launch',
  description: 'desc',
  objectives: ['Awareness'],
  status: 'ACTIVE' as const,
  budget: { min: 10000, max: 50000, currency: 'INR' },
  timeline: { startDate: new Date('2026-08-01'), endDate: new Date('2026-09-01') },
  platforms: ['INSTAGRAM' as const],
  contentTypes: ['REEL' as const],
  requirements: [],
  isPrivate: false,
  maxCollaborators: 5,
  createdBy: 'u1',
  createdAt: new Date('2026-08-01'),
  updatedAt: new Date('2026-08-01'),
};

beforeEach(() => {
  vi.clearAllMocks();
  campaignsGet.mockResolvedValue(campaign);
  dealsList.mockResolvedValue([]);
});

describe('campaign detail — budget/fee block (live mode)', () => {
  it('never renders the invented Creator Pay / GST / Contingency split, and shows the real fee rate', async () => {
    // F-0294 — `copy` is the CEO-mandated server-authored disclosure text
    // (BrandPlatformFeeService.BRAND_FEE_COPY). It is a fixed literal, not derived from
    // feeBps/feePercent at runtime, so it intentionally does not "agree" with the 7% rate
    // below — the page must render it verbatim regardless.
    const serverCopy = 'Platform fee (10%) — charged only when your campaign goes live.';
    brandPlatformFee.mockResolvedValue({
      feeBps: 700,
      feePercent: 7,
      source: 'GLOBAL_DEFAULT',
      copy: serverCopy,
    });

    render(
      <MemoryRouter>
        <BrandCampaignDetailPage />
      </MemoryRouter>,
    );

    await screen.findByText('Summer Launch');
    // Real total budget always shown.
    await waitFor(() => expect(screen.getByText('₹50,000')).toBeInTheDocument());

    // The fabricated split lines must never appear.
    expect(screen.queryByText(/Creator Pay/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/Contingency/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/^GST/)).not.toBeInTheDocument();
    // The old hardcoded "10%" label must not survive even by coincidence — the real fetched
    // rate for this workspace is 7%, not 10%.
    expect(screen.queryByText(/Platform Fee \(10%/)).not.toBeInTheDocument();

    // The real server rate is used instead, clearly marked as an estimate.
    await waitFor(() => expect(screen.getByText(/Platform Fee \(7%, est\.\)/)).toBeInTheDocument());
    expect(screen.getByText(/Estimate only, not a quote/i)).toBeInTheDocument();

    // F-0294 — the server's real `copy` field must reach the screen verbatim...
    await waitFor(() => expect(screen.getByText(serverCopy)).toBeInTheDocument());
    // ...and the client-invented substitute sentence ("on real spend") must never stand in
    // for it — the server copy makes no such claim.
    expect(screen.queryByText(/Actual fees are charged on real spend/i)).not.toBeInTheDocument();
    // The unreachable "your plan's current rate" branch must also be gone — the backend only
    // ever constructs SOURCE_GLOBAL_DEFAULT (BrandPlatformFeeService.getCurrentFee).
    expect(screen.queryByText(/your plan.s current rate/i)).not.toBeInTheDocument();
  });

  it('renders nothing extra when the server sends an empty copy string', async () => {
    brandPlatformFee.mockResolvedValue({
      feeBps: 1500,
      feePercent: 15,
      source: 'GLOBAL_DEFAULT',
      copy: '',
    });

    render(
      <MemoryRouter>
        <BrandCampaignDetailPage />
      </MemoryRouter>,
    );

    await screen.findByText('Summer Launch');
    await waitFor(() => expect(screen.getByText(/Platform Fee \(15%, est\.\)/)).toBeInTheDocument());
    // No fabricated stand-in text appears just because copy was empty.
    expect(screen.queryByText(/Actual fees are charged on real spend/i)).not.toBeInTheDocument();
  });

  it('falls back to an honest "not available" state when the fee endpoint fails, never to a guess', async () => {
    brandPlatformFee.mockRejectedValue(new Error('network error'));

    render(
      <MemoryRouter>
        <BrandCampaignDetailPage />
      </MemoryRouter>,
    );

    await screen.findByText('Summer Launch');
    await waitFor(() => expect(screen.getByText('₹50,000')).toBeInTheDocument());

    expect(await screen.findByText(/Fee breakdown isn.t available yet/i)).toBeInTheDocument();
    expect(screen.queryByText(/Platform Fee \(/)).not.toBeInTheDocument();
    expect(screen.queryByText(/Creator Pay/i)).not.toBeInTheDocument();
  });
});
