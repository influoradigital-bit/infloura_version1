/**
 * Discovery → message redirect.
 *
 * Reported 2026-08-03: "brand tries to message through the discover creator option, then it's
 * not redirect message." After a successful offer/invite the modal closes and
 * `creator-discovery.tsx:536` runs `navigate('/brand/chat')` with NO query string.
 *
 * `/brand/chat` (BrandChatPage) selects the open conversation purely from the URL:
 *   brand-chat.tsx:921       early return while the deal list is still empty
 *   brand-chat.tsx:922-926   match = ?deal= by deal id, else ?creator= by creator id, else undefined
 *   brand-chat.tsx:927       setSelectedDeal(match || liveDealRooms[0])
 * With neither param there is no match, so the brand lands on `liveDealRooms[0]` — an arbitrary
 * OTHER creator — and because :921 is guarded on `selectedDeal`, that wrong pick latches: no
 * later loadDealRooms() refresh can correct it.
 *
 * The gate asserts `?deal=<the id the API just returned>`, not merely "some param". A
 * `?creator=<CreatorProfile id>` would satisfy a looser assertion and STILL mis-select, because
 * `ChatDealRoom.creatorId` is `deal.counterpartyId` — a User id (DealService.resolveCounterparty
 * returns `collaboration.getCreatorId()` and resolves it with `findByUserId`). Both submit paths
 * already return a usable collaboration id: `DealResponse.id` and
 * `CreatorDtos.InviteResponse.collaborationId`.
 *
 * Run: npx vitest run src/components/brand/discover/creator-discovery-redirect.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { CreatorDiscovery } from './creator-discovery';

const navigateMock = vi.fn();
vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom');
  return { ...actual, useNavigate: () => (...a: unknown[]) => navigateMock(...a) };
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

describe('CreatorDiscovery — redirect to the conversation it just opened', () => {
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

  it('sends a priced offer, then redirects to THAT creator’s deal room', async () => {
    const user = userEvent.setup({ delay: null });
    renderDiscovery();
    const dialog = await openModalAndPickCampaign(user);
    await user.click(within(dialog).getByRole('button', { name: /Next: Review/i }));
    await user.click(within(dialog).getByRole('button', { name: /Send/i }));

    await waitFor(() => expect(dealsCreate).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(navigateMock).toHaveBeenCalled());

    const target = String(navigateMock.mock.calls.at(-1)?.[0]);
    // brand-chat.tsx:923 selects on ?deal= against ChatDealRoom.id. POST /deals returns that id.
    // Asserting the returned id specifically — a bare ?creator= carries a CreatorProfile id,
    // which brand-chat.tsx:925 compares against a User id and never matches.
    expect(target).toContain('/brand/chat');
    expect(target).toMatch(/[?&]deal=deal_1(&|$)/);
  });

  it('sends an unpriced invite, then redirects to THAT creator’s deal room', async () => {
    const user = userEvent.setup({ delay: null });
    renderDiscovery();
    const dialog = await openModalAndPickCampaign(user);

    const budget = within(dialog).getByDisplayValue('40000');
    await user.clear(budget);
    await user.type(budget, '0');

    await user.click(within(dialog).getByRole('button', { name: /Next: Review/i }));
    await user.click(within(dialog).getByRole('button', { name: /Send/i }));

    await waitFor(() => expect(creatorsInvite).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(navigateMock).toHaveBeenCalled());

    const target = String(navigateMock.mock.calls.at(-1)?.[0]);
    // CreatorDtos.InviteResponse(collaborationId, status, createdAt) — same id space as
    // ChatDealRoom.id, so the invite path can identify its room too.
    expect(target).toContain('/brand/chat');
    expect(target).toMatch(/[?&]deal=col_1(&|$)/);
  });
});
