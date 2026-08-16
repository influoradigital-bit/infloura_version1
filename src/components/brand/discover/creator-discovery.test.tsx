/**
 * Discovery invite modal — invite vs priced offer.
 *
 * `POST /deals` had been live and tested with ZERO UI callers, while this modal collected
 * deliverables, budget, deadline and usage rights across three steps and then threw all of it
 * away on `creators.invite`. Wired 2026-07-26 (CEO approval, CTO design).
 *
 * The contract these tests pin down: both endpoints are campaign-scoped and write the SAME
 * Collaboration row keyed on (campaignId, creatorId) — they 409 against each other — so exactly
 * one fires per submit, chosen by whether the brand actually priced the offer.
 *
 * Run: npx vitest run src/components/brand/discover/creator-discovery.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { CreatorDiscovery } from './creator-discovery';

vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom');
  return { ...actual, useNavigate: () => vi.fn() };
});

const toastMock = vi.fn();
// Both members must be lazy: vi.mock factories are hoisted above the const above, so a bare
// `toast: toastMock` is evaluated before initialization and throws at import time.
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

/**
 * Must satisfy every default filter in `filteredCreators` or the card never renders:
 * `totalFollowers` (not `followers`) within [0, 10_000_000], `averageRate` within [5_000,
 * 200_000], `engagementRate` within [0, 15].
 */
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

function renderDiscovery() {
  return render(
    <MemoryRouter initialEntries={['/brand/discover']}>
      <CreatorDiscovery />
    </MemoryRouter>,
  );
}

/** Opens the modal and advances past step 1 (campaign selection). */
async function openModalAndPickCampaign(user: ReturnType<typeof userEvent.setup>) {
  // F-0217: the creator grid appears only after a 300ms debounce, the mocked search, and a
  // render of the full result grid. Waiting for all three inside one findBy budget is what made
  // this the suite's most load-sensitive assertion. Wait for the fetch first, so each step gets
  // its own budget and a failure says which stage actually stalled.
  await waitFor(() => expect(creatorsSearch).toHaveBeenCalled());
  await user.click(await screen.findByRole('button', { name: /^Invite$/i }));

  const dialog = await screen.findByRole('dialog');
  await user.click(within(dialog).getByRole('combobox'));
  await user.click(await screen.findByRole('option', { name: /Summer Launch/i }));
  await user.click(within(dialog).getByRole('button', { name: /Next: Define Proposal/i }));
  return dialog;
}

describe('CreatorDiscovery — invite vs priced offer', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    creatorsSearch.mockResolvedValue({
      creators: [CREATOR],
      meta: { page: 1, limit: 20, hasMore: false },
    });
    campaignsList.mockResolvedValue({
      campaigns: [{ id: 'camp_1', title: 'Summer Launch', status: 'ACTIVE' }],
      meta: { page: 1, limit: 50, hasMore: false },
    });
    creatorsInvite.mockResolvedValue({ collaborationId: 'col_1' });
    dealsCreate.mockResolvedValue({ id: 'deal_1' });
  });

  it('sends a priced offer via deals.create when a budget is set', async () => {
    const user = userEvent.setup({ delay: null });
    renderDiscovery();

    const dialog = await openModalAndPickCampaign(user);

    // Budget prefills from the creator's average rate (40000), so the offer is priced by default.
    await user.click(within(dialog).getByRole('button', { name: /Next: Review/i }));
    await user.click(within(dialog).getByRole('button', { name: /Send/i }));

    await waitFor(() => expect(dealsCreate).toHaveBeenCalledTimes(1));
    expect(creatorsInvite).not.toHaveBeenCalled();

    const payload = dealsCreate.mock.calls[0][0];
    expect(payload.campaignId).toBe('camp_1');
    // Must be the CreatorProfile id — the server resolves it, a User id would 404.
    expect(payload.creatorId).toBe(CREATOR.id);
    expect(payload.amount).toBe(40000);
    // Local state uses `count`; DealDtos.DeliverableSlot expects `qty`.
    expect(payload.deliverables).toEqual([{ type: 'REEL', qty: 1 }]);
    expect(payload.usageRights).toBe('3_MONTHS');
    // Removed 2026-07-26 — the server discarded it, so we must not send or imply it.
    expect(payload).not.toHaveProperty('exclusivity');
  });

  it('falls back to creators.invite when the budget is cleared to zero', async () => {
    const user = userEvent.setup({ delay: null });
    renderDiscovery();

    const dialog = await openModalAndPickCampaign(user);

    // Two spinbuttons in this step (deliverable count and budget) and the budget Input carries no
    // label association — target it by its prefilled value, the creator's average rate.
    const budget = within(dialog).getByDisplayValue('40000');
    await user.clear(budget);
    await user.type(budget, '0');

    await user.click(within(dialog).getByRole('button', { name: /Next: Review/i }));
    await user.click(within(dialog).getByRole('button', { name: /Send/i }));

    await waitFor(() => expect(creatorsInvite).toHaveBeenCalledTimes(1));
    // An unpriced approach must NOT create a priced proposal — the two 409 against each other.
    expect(dealsCreate).not.toHaveBeenCalled();
    expect(creatorsInvite).toHaveBeenCalledWith(CREATOR.id, 'camp_1', undefined);
  });

  it('explains a COLLABORATION_EXISTS conflict instead of asking the brand to retry', async () => {
    const { ApiError } = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
    dealsCreate.mockRejectedValue(
      new ApiError('COLLABORATION_EXISTS', 'A deal already exists for this campaign and creator', 409),
    );
    const user = userEvent.setup({ delay: null });
    renderDiscovery();

    const dialog = await openModalAndPickCampaign(user);
    await user.click(within(dialog).getByRole('button', { name: /Next: Review/i }));
    await user.click(within(dialog).getByRole('button', { name: /Send/i }));

    await waitFor(() => expect(toastMock).toHaveBeenCalled());
    const call = toastMock.mock.calls.at(-1)?.[0];
    expect(call.title).toBe('Offer failed');
    expect(call.description).toMatch(/already on this campaign/i);
  });
});
