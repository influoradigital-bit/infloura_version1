/**
 * Campaign detail — contract controls, end to end through the rendered page (F-0218).
 *
 * The unit test alongside this one pins the mapper. This one exists because an independent
 * check pointed out that the two behaviours most likely to rot — the deep-link out to
 * /brand/contracts and the Generate-contract dialog — were verified only by reading the source.
 * Neither had a test that would notice if the wiring were removed.
 *
 * Live mode only: the whole contract UI sits behind isApiLive(), so the mock-mode card branch
 * renders none of it.
 *
 * Run: npx vitest run src/pages/brand-campaign-detail.contract-ui.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
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
const contractsGenerate = vi.fn();

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
      contracts: {
        ...actual.api.contracts,
        generate: (...a: unknown[]) => contractsGenerate(...a),
      },
    },
  };
});

import BrandCampaignDetailPage from './brand-campaign-detail';

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

function deal(over: Record<string, unknown>) {
  return {
    id: 'deal-1',
    campaignId: 'camp-1',
    campaignName: 'Summer Launch',
    counterpartyId: 'cr-1',
    counterpartyName: 'Priya Sharma',
    status: 'CONTRACTED',
    dealValue: 25000,
    currency: 'INR',
    unreadCount: 0,
    deliverablesDone: 1,
    deliverablesTotal: 4,
    ...over,
  };
}

/** The contract controls live on the "Active" tab, which is not the default. */
async function openActiveTab(user: ReturnType<typeof userEvent.setup>) {
  const tab = await screen.findByRole('tab', { name: /Active/i });
  await user.click(tab);
}

beforeEach(() => {
  vi.clearAllMocks();
  campaignsGet.mockResolvedValue(campaign);
});

describe('campaign detail — contract controls (live mode)', () => {
  it('deep-links a contracted deal to that exact contract, not the contracts list', async () => {
    const user = userEvent.setup({ delay: null });
    dealsList.mockResolvedValue([
      deal({ status: 'CONTRACTED', contractId: 'CTR_9', contractStatus: 'PENDING_SIGNATURES' }),
    ]);

    render(
      <MemoryRouter>
        <BrandCampaignDetailPage />
      </MemoryRouter>,
    );

    await openActiveTab(user);
    // Label states the next action, driven by the real ContractStatus.
    const btn = await screen.findByRole('button', { name: /Sign contract/i });
    await user.click(btn);

    // The id must be on the URL — landing on /brand/contracts alone is the bug this fixes.
    expect(navigateMock).toHaveBeenCalledWith('/brand/contracts?contract=CTR_9');
  });

  it('offers Generate contract only at TERMS_AGREED with no contract, and POSTs the deal id', async () => {
    const user = userEvent.setup({ delay: null });
    contractsGenerate.mockResolvedValue({ id: 'CTR_new' });
    dealsList.mockResolvedValue([deal({ id: 'deal-7', status: 'TERMS_AGREED' })]);

    render(
      <MemoryRouter>
        <BrandCampaignDetailPage />
      </MemoryRouter>,
    );

    await openActiveTab(user);
    await user.click(await screen.findByRole('button', { name: /Generate contract/i }));

    // Dialog opens with the milestone editor prefilled from the real deal value.
    const send = await screen.findByRole('button', { name: /Send contract for signature/i });
    await user.click(send);

    await waitFor(() => expect(contractsGenerate).toHaveBeenCalledTimes(1));
    const payload = contractsGenerate.mock.calls[0][0] as {
      collaborationId: string;
      milestones: Array<{ sequenceNo: number; amount: number }>;
    };
    // collaborationId IS the deal id — the server keys the contract off the Collaboration.
    expect(payload.collaborationId).toBe('deal-7');
    expect(payload.milestones[0].sequenceNo).toBe(1);
    expect(payload.milestones[0].amount).toBe(25000);
  });

  it('says "No contract yet" rather than offering a control the server would reject', async () => {
    const user = userEvent.setup({ delay: null });
    // IN_PROGRESS with no contract: generating one is not the right next step here.
    dealsList.mockResolvedValue([deal({ status: 'IN_PROGRESS' })]);

    render(
      <MemoryRouter>
        <BrandCampaignDetailPage />
      </MemoryRouter>,
    );

    await openActiveTab(user);
    expect(await screen.findByText('No contract yet')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Generate contract/i })).toBeNull();
    expect(screen.queryByRole('button', { name: /contract$/i })).toBeNull();
  });

  it('surfaces a 403 in the operator’s own words instead of a generic failure', async () => {
    const user = userEvent.setup({ delay: null });
    const { ApiError } = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
    // ApiError is (code, message, status) — api.ts:186. Getting this order wrong makes the
    // 403 branch silently untaken, which is exactly what this test exists to catch.
    contractsGenerate.mockRejectedValue(
      new ApiError('FORBIDDEN', 'Insufficient workspace permissions', 403),
    );
    dealsList.mockResolvedValue([deal({ id: 'deal-7', status: 'TERMS_AGREED' })]);

    render(
      <MemoryRouter>
        <BrandCampaignDetailPage />
      </MemoryRouter>,
    );

    await openActiveTab(user);
    await user.click(await screen.findByRole('button', { name: /Generate contract/i }));
    await user.click(await screen.findByRole('button', { name: /Send contract for signature/i }));

    await waitFor(() => expect(toastMock).toHaveBeenCalled());
    const arg = toastMock.mock.calls.at(-1)?.[0] as { title: string; description: string };
    expect(arg.title).toMatch(/can't create this contract/i);
    expect(arg.description).toMatch(/Owner, Admin or Manager/i);
  });
});
