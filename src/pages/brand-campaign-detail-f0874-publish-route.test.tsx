/**
 * F-0874 — BrandCampaignDetailPage's "Resume Campaign" action.
 *
 * Before: any non-ACTIVE, non-completed status (including DRAFT) showed "Resume Campaign" and
 * sent `api.campaigns.update(id, { status: 'ACTIVE' })` straight to the server. The server's
 * activation guard correctly refuses an unfunded DRAFT (F-0848), so the brand saw a raw error
 * instead of a way to secure the funds — and the label lied about a DRAFT (never live before)
 * being "resumed".
 *
 * Fixed: a DRAFT now shows "Publish campaign", which opens the same inline
 * "Secure the funds to publish" step the create flows use (`SecureAndPublishStep`,
 * `checkExistingFunds` on, since a draft reopened here may already have secured funds from an
 * earlier attempt — the backend now returns an existing FUNDED hold as a normal success). A
 * PAUSED campaign (funds were already secured when it first went ACTIVE) keeps the original
 * direct-resume behavior — "Resume Campaign" still sends `update(id, { status: 'ACTIVE' })`
 * straight away.
 *
 * Every assertion drives the real menu item / button and checks the API call and its arguments.
 *
 * Run: npx vitest run src/pages/brand-campaign-detail-f0874-publish-route.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import BrandCampaignDetailPage from './brand-campaign-detail';
import type { Campaign } from '@/lib/types';

const toastMock = vi.fn();
vi.mock('@/hooks/use-toast', () => ({
  useToast: () => ({ toast: (...a: unknown[]) => toastMock(...a) }),
}));

const campaignsGet = vi.fn();
const campaignsUpdate = vi.fn();
const campaignsAnalytics = vi.fn();
const dealsList = vi.fn();
const escrowList = vi.fn();
const fundEscrow = vi.fn();

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
        update: (...a: unknown[]) => campaignsUpdate(...a),
        analytics: (...a: unknown[]) => campaignsAnalytics(...a),
      },
      deals: { ...actual.api.deals, list: (...a: unknown[]) => dealsList(...a) },
      wallet: { ...actual.api.wallet, escrowList: (...a: unknown[]) => escrowList(...a) },
    },
  };
});

vi.mock('@/lib/meera-api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/meera-api')>('@/lib/meera-api');
  return { ...actual, meeraApi: { ...actual.meeraApi, fundEscrow: (...a: unknown[]) => fundEscrow(...a) } };
});

function makeCampaign(status: Campaign['status'], id = 'camp_1'): Campaign {
  return {
    id,
    workspaceId: 'ws_1',
    title: 'Diwali Skincare Reels',
    description: 'Promote our new serum line',
    objectives: ['Brand awareness'],
    status,
    budget: { min: 10000, max: 25000, currency: 'INR' },
    timeline: { startDate: new Date('2026-01-01'), endDate: new Date('2026-02-01') },
    platforms: ['INSTAGRAM'],
    contentTypes: ['REEL'],
    requirements: [],
    targetAudience: {},
    isPrivate: false,
    maxCollaborators: 5,
    createdBy: 'user_1',
    createdAt: new Date('2026-01-01'),
    updatedAt: new Date('2026-01-01'),
  } as Campaign;
}

function renderPage(id = 'camp_1') {
  return render(
    <MemoryRouter initialEntries={[`/brand/campaigns/${id}`]}>
      <Routes>
        <Route path="/brand/campaigns/:id" element={<BrandCampaignDetailPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

async function openActionMenu(user: ReturnType<typeof userEvent.setup>) {
  // Icon-only trigger — same pattern as brand-campaign-detail-active-edit-gate.test.tsx: the
  // last unnamed button on the page (after the unnamed back-arrow button) opens this menu.
  const unnamedButtons = screen.getAllByRole('button', { name: '' });
  await user.click(unnamedButtons[unnamedButtons.length - 1]);
}

describe('BrandCampaignDetailPage — F-0874 Resume/Publish action', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    dealsList.mockResolvedValue([]);
    campaignsAnalytics.mockResolvedValue(null);
    escrowList.mockResolvedValue([]);
  });

  it('a DRAFT campaign shows "Publish campaign" (not "Resume"), and it opens the secure-the-funds step instead of sending ACTIVE straight to the server', async () => {
    const CAMPAIGN_ID = 'camp_draft';
    campaignsGet.mockResolvedValue(makeCampaign('DRAFT', CAMPAIGN_ID));
    // The backend contract this fix relies on: a second funds attempt for a campaign that
    // already has an active hold returns the EXISTING hold as a normal success — simulated here
    // by escrowList already carrying a FUNDED hold for this campaign (e.g. a prior attempt).
    escrowList.mockResolvedValue([
      { escrowHoldId: 'h_1', status: 'FUNDED', amount: 25000, currency: 'INR', campaignId: CAMPAIGN_ID, milestoneId: null, fundedAt: null },
    ]);
    const user = userEvent.setup({ delay: null });
    renderPage(CAMPAIGN_ID);

    await screen.findByText('Diwali Skincare Reels');
    await openActionMenu(user);

    expect(screen.queryByText(/^resume campaign$/i)).not.toBeInTheDocument();
    await user.click(await screen.findByText(/^publish campaign$/i));

    // Opening the step must not, by itself, activate the campaign.
    expect(campaignsUpdate).not.toHaveBeenCalled();
    expect(await screen.findByRole('heading', { name: /secure the funds to publish/i })).toBeInTheDocument();

    // Already-FUNDED hold found — the fund control never shows, and the step goes straight to
    // an honest "Publish campaign" retry, never re-offering funding.
    const step = await screen.findByTestId('secure-and-publish-step');
    expect(await within(step).findByText(/funds secured for this campaign/i)).toBeInTheDocument();
    expect(within(step).queryByRole('button', { name: /fund & go live/i })).not.toBeInTheDocument();

    campaignsUpdate.mockResolvedValue({ id: CAMPAIGN_ID, status: 'ACTIVE' });
    await user.click(within(step).getByRole('button', { name: /^publish campaign$/i }));

    await waitFor(() => expect(campaignsUpdate).toHaveBeenCalledWith(CAMPAIGN_ID, { status: 'ACTIVE' }));
    expect(campaignsUpdate).toHaveBeenCalledTimes(1);
    expect(fundEscrow).not.toHaveBeenCalled();
  });

  it('a PAUSED campaign (funds already secured) still shows "Resume Campaign" and resumes directly — no secure-the-funds step', async () => {
    const CAMPAIGN_ID = 'camp_paused';
    campaignsGet.mockResolvedValue(makeCampaign('PAUSED', CAMPAIGN_ID));
    campaignsUpdate.mockResolvedValue({ id: CAMPAIGN_ID, status: 'ACTIVE' });
    const user = userEvent.setup({ delay: null });
    renderPage(CAMPAIGN_ID);

    await screen.findByText('Diwali Skincare Reels');
    await openActionMenu(user);

    expect(screen.queryByText(/^publish campaign$/i)).not.toBeInTheDocument();
    await user.click(await screen.findByText(/^resume campaign$/i));

    await waitFor(() => expect(campaignsUpdate).toHaveBeenCalledWith(CAMPAIGN_ID, { status: 'ACTIVE' }));
    expect(campaignsUpdate).toHaveBeenCalledTimes(1);
    // The direct-resume path never mounts the funds-check step or looks up existing holds.
    expect(screen.queryByTestId('secure-and-publish-step')).not.toBeInTheDocument();
    expect(escrowList).not.toHaveBeenCalled();
  });

  it('EV-005: a PAUSED resume the server refuses with ESCROW_NOT_FUNDED opens the secure-the-funds step, not an error toast', async () => {
    const CAMPAIGN_ID = 'camp_paused_unfunded';
    campaignsGet.mockResolvedValue(makeCampaign('PAUSED', CAMPAIGN_ID));
    const { ApiError } = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
    campaignsUpdate.mockRejectedValueOnce(
      new ApiError('ESCROW_NOT_FUNDED', 'Campaign has no secured payment in FUNDED status', 409),
    );
    const user = userEvent.setup({ delay: null });
    renderPage(CAMPAIGN_ID);

    await screen.findByText('Diwali Skincare Reels');
    await openActionMenu(user);
    await user.click(await screen.findByText(/^resume campaign$/i));

    expect(await screen.findByRole('heading', { name: /secure the funds to publish/i })).toBeInTheDocument();
    expect(campaignsUpdate).toHaveBeenCalledTimes(1);
    expect(toastMock).not.toHaveBeenCalledWith(expect.objectContaining({ title: 'Could not update campaign' }));
  });
});
