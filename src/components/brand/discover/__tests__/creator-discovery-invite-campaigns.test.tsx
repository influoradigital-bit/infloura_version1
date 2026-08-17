/**
 * F-0256 — mock-seed-in-live-selector.
 * ----------------------------------------------------------------------------
 * The invite dialog's campaign `<Select>` used to seed `inviteCampaigns` with three fabricated
 * campaigns (`{ id: 'c1', name: 'Diwali Collection Launch 2024', status: 'ACTIVE' }`, …) as
 * INITIAL React state, completely ungated by `isApiLive()`. In live mode that fixture sat in
 * the dropdown, fully selectable, for however long `GET /campaigns` took to resolve. A brand
 * that opened the invite modal and picked a campaign before the real list arrived could submit
 * an invite/offer against a `campaignId` that does not exist on the server.
 *
 * The fix: in live mode the selector starts EMPTY and loading, and only ever shows campaigns
 * that came back from `api.campaigns.list`. The mock fixture is still used, but only when
 * `!isApiLive()` (mock mode has no live query to wait on).
 *
 * Two guarantees:
 *   A. While `api.campaigns.list` is still pending, the campaign combobox is disabled and none
 *      of the fabricated mock campaign names are present in the DOM.
 *   B. Once the real list resolves, the combobox enables and offers ONLY the real campaigns —
 *      the mock names never appear, before or after.
 *
 * Run: npx vitest run src/components/brand/discover/__tests__/creator-discovery-invite-campaigns.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { CreatorDiscovery } from '../creator-discovery';

vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom');
  return { ...actual, useNavigate: () => vi.fn() };
});

const toastMock = vi.fn();
vi.mock('@/hooks/use-toast', () => ({
  useToast: () => ({ toast: (...a: unknown[]) => toastMock(...a) }),
  toast: (...a: unknown[]) => toastMock(...a),
}));

const creatorsSearch = vi.fn();
const creatorsInvite = vi.fn();
const dealsCreate = vi.fn();
const campaignsList = vi.fn();

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    isApiLive: () => true,
    api: {
      creators: {
        search: (...a: unknown[]) => creatorsSearch(...a),
        invite: (...a: unknown[]) => creatorsInvite(...a),
        toggleSaved: vi.fn().mockResolvedValue({ saved: true }),
        featured: vi.fn().mockResolvedValue({ featured: [] }),
      },
      deals: { create: (...a: unknown[]) => dealsCreate(...a) },
      campaigns: { list: (...a: unknown[]) => campaignsList(...a) },
    },
  };
});

/** Same fixture shape used by the sibling invite tests — satisfies every default filter. */
const CREATOR = {
  id: 'cp_01HCREATORPROFILE',
  displayName: 'Aarti Menon',
  location: 'Mumbai',
  averageRate: 40000,
  totalFollowers: 120000,
  engagementRate: 4.2,
  verified: true,
  platforms: [],
  categories: [],
  languages: [],
};

// The exact fabricated names from the removed mock seed — must never reach the DOM in live mode.
const MOCK_CAMPAIGN_NAMES = [
  'Diwali Collection Launch 2024',
  'Summer Skincare Range',
  'Fitness App Promotion',
];

function renderDiscovery() {
  return render(
    <MemoryRouter initialEntries={['/brand/discover']}>
      <CreatorDiscovery />
    </MemoryRouter>,
  );
}

describe('F-0256 — invite campaign selector never seeds fabricated campaigns in live mode', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    creatorsSearch.mockResolvedValue({
      creators: [CREATOR],
      meta: { page: 1, limit: 20, hasMore: false },
    });
  });

  it('starts empty/disabled while the real campaign list is in flight, then shows only real campaigns', async () => {
    let resolveList: (v: unknown) => void = () => {};
    campaignsList.mockReturnValue(
      new Promise((res) => {
        resolveList = res;
      }),
    );

    const user = userEvent.setup({ delay: null });
    renderDiscovery();

    await waitFor(() => expect(creatorsSearch).toHaveBeenCalled());
    await user.click(await screen.findByRole('button', { name: /^Invite$/i }));

    const dialog = await screen.findByRole('dialog');
    const trigger = within(dialog).getByRole('combobox');

    // Guarantee A — still loading: disabled, and none of the fabricated names exist anywhere.
    expect(trigger).toBeDisabled();
    for (const name of MOCK_CAMPAIGN_NAMES) {
      expect(screen.queryByText(name)).not.toBeInTheDocument();
    }

    // Now the real list resolves.
    resolveList({
      campaigns: [{ id: 'camp_real', title: 'Real Diwali Push', status: 'ACTIVE' }],
      meta: { page: 1, limit: 50, hasMore: false },
    });

    await waitFor(() => expect(trigger).not.toBeDisabled());
    await user.click(trigger);

    // Guarantee B — only the real campaign is offered.
    expect(await screen.findByRole('option', { name: /Real Diwali Push/i })).toBeInTheDocument();
    for (const name of MOCK_CAMPAIGN_NAMES) {
      expect(screen.queryByText(name)).not.toBeInTheDocument();
    }
  });

  it('never lets the brand submit against a fabricated campaign id', async () => {
    // The real list resolves to EMPTY — a brand with no active campaigns. If the mock seed were
    // still live, the three fake campaigns would be exactly what fills this gap.
    campaignsList.mockResolvedValue({ campaigns: [], meta: { page: 1, limit: 50, hasMore: false } });

    const user = userEvent.setup({ delay: null });
    renderDiscovery();

    await waitFor(() => expect(creatorsSearch).toHaveBeenCalled());
    await user.click(await screen.findByRole('button', { name: /^Invite$/i }));
    const dialog = await screen.findByRole('dialog');

    await waitFor(() => expect(campaignsList).toHaveBeenCalled());
    for (const name of MOCK_CAMPAIGN_NAMES) {
      expect(screen.queryByText(name)).not.toBeInTheDocument();
    }

    // Nothing real to select — the submit control must not be a way through.
    expect(within(dialog).getByRole('button', { name: /Next: Define Proposal/i })).toBeDisabled();
  });
});
