/**
 * T-CREATORCONNECT-0902 Q6.4 (High) regression pin — the type-picker half.
 *
 * Before the fix, choosing the Hype tile after arriving from Discover's "Connect this creator"
 * handoff silently dropped `?creatorId=`/`?ig=` on the floor, so the banner's promise ("they'll be
 * invited when you publish") was broken with no explanation. This pins:
 *   - choosing Hype forwards `?creatorId=`/`?ig=` onto `/brand/campaigns/new/hype`
 *   - the picker's own handoff banner no longer promises an unconditional invite, since Hype
 *     (which cannot invite a specific creator) is always one of the visible tiles
 *
 * Run: npx vitest run src/pages/brand-new-campaign.hype-handoff.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import BrandNewCampaignPage from './brand-new-campaign';

const navigateMock = vi.fn();
vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom');
  return { ...actual, useNavigate: () => navigateMock };
});

const getProfileMock = vi.fn();
const templatesListMock = vi.fn();

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    isApiLive: () => false, // keeps BrandKycPrompt's effect inert — no network calls needed
    api: {
      ...actual.api,
      creators: { ...actual.api.creators, getProfile: (...a: unknown[]) => getProfileMock(...a) },
      campaignTemplates: { ...actual.api.campaignTemplates, list: (...a: unknown[]) => templatesListMock(...a) },
    },
  };
});

function renderPicker(initialEntry: string) {
  return render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <BrandNewCampaignPage />
    </MemoryRouter>,
  );
}

describe('BrandNewCampaignPage — Hype handoff (Q6.4)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    templatesListMock.mockResolvedValue([]);
    getProfileMock.mockResolvedValue(null);
  });

  it('choosing Hype forwards ?creatorId=&ig= onto the Hype page instead of dropping them', async () => {
    const user = userEvent.setup({ delay: null });
    renderPicker('/brand/campaigns/new?creatorId=cp_01HLINKED&ig=foodie.mumbai');

    const hypeTile = await screen.findByRole('button', { name: /hype campaign/i });
    await user.click(hypeTile);

    await waitFor(() => expect(navigateMock).toHaveBeenCalledTimes(1));
    expect(navigateMock).toHaveBeenCalledWith('/brand/campaigns/new/hype?creatorId=cp_01HLINKED&ig=foodie.mumbai');
  });

  it('forwards only what is present — no ig param means no &ig= on the Hype URL', async () => {
    const user = userEvent.setup({ delay: null });
    renderPicker('/brand/campaigns/new?creatorId=cp_01HLINKED');

    const hypeTile = await screen.findByRole('button', { name: /hype campaign/i });
    await user.click(hypeTile);

    await waitFor(() => expect(navigateMock).toHaveBeenCalledTimes(1));
    expect(navigateMock).toHaveBeenCalledWith('/brand/campaigns/new/hype?creatorId=cp_01HLINKED');
  });

  it('the picker banner qualifies its invite promise — Hype (always a visible tile) cannot keep it', async () => {
    renderPicker('/brand/campaigns/new?creatorId=cp_01HLINKED&ig=foodie.mumbai');

    expect(await screen.findByText(/@foodie\.mumbai/)).toBeInTheDocument();
    expect(screen.getByText(/hype campaigns can.t invite a specific creator/i)).toBeInTheDocument();
  });

  it('no creatorId param — no handoff banner at all', async () => {
    renderPicker('/brand/campaigns/new');

    await screen.findByRole('button', { name: /hype campaign/i });
    expect(screen.queryByText(/they.ll be invited/i)).not.toBeInTheDocument();
  });
});
