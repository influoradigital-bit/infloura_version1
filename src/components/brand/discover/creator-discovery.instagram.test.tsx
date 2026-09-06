/**
 * Discovery — Instagram creators tab (T-CREATORCONNECT-0902).
 *
 * Pins down the three states the contract's `done_when` calls out:
 *   - an UNVERIFIED/INVITED external creator renders the amber "Unverified with Influora" badge,
 *     and "Connect this creator" → dialog → submit calls `api.externalCreators.connect` with id.
 *   - a JOINED creator renders "Verified with Influora" and a `/brand/campaigns/new?creatorId=`
 *     link, never the "Connect" CTA.
 *   - a 503 from `GET /creators/external` renders the honest "unavailable" copy with zero cards
 *     — never a mock/fabricated creator (F-0259/F-0260).
 *
 * Run: npx vitest run src/components/brand/discover/creator-discovery.instagram.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { CreatorDiscovery } from './creator-discovery';
import type { ExternalCreator } from '@/lib/api';

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
const externalList = vi.fn();
const externalLookup = vi.fn();
const externalConnect = vi.fn();
const externalConnectionRequests = vi.fn();

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    isApiLive: () => true,
    api: {
      creators: {
        search: (...a: unknown[]) => creatorsSearch(...a),
        // F-0660 — see creator-discovery.test.tsx's identical comment.
        searchWithFacets: (...a: unknown[]) => creatorsSearch(...a),
        invite: vi.fn(),
        toggleSaved: vi.fn().mockResolvedValue({ saved: true }),
        featured: vi.fn().mockResolvedValue({ featured: [] }),
      },
      deals: { create: vi.fn() },
      campaigns: { list: vi.fn().mockResolvedValue({ campaigns: [], meta: { page: 1, limit: 50, hasMore: false } }) },
      externalCreators: {
        list: (...a: unknown[]) => externalList(...a),
        lookup: (...a: unknown[]) => externalLookup(...a),
        connect: (...a: unknown[]) => externalConnect(...a),
        connectionRequests: (...a: unknown[]) => externalConnectionRequests(...a),
      },
    },
  };
});

const UNVERIFIED_CREATOR: ExternalCreator = {
  id: 'ec_01HUNVERIFIED',
  source: 'BUSINESS_DISCOVERY',
  igUsername: 'foodie.mumbai',
  displayName: 'Foodie Mumbai',
  bio: 'Street food across the city.',
  avatarUrl: null,
  followers: 42000,
  mediaCount: 120,
  engagementRate: 3.1,
  country: 'India',
  categories: ['Food'],
  status: 'UNVERIFIED',
  verifiedWithInfluora: false,
  linkedCreatorProfileId: null,
  connectionStatus: null,
  connectionRequestId: null,
  lastSyncedAt: null,
  invitedAt: null,
};

const JOINED_CREATOR: ExternalCreator = {
  ...UNVERIFIED_CREATOR,
  id: 'ec_01HJOINED',
  igUsername: 'travel.with.raj',
  status: 'JOINED',
  verifiedWithInfluora: true,
  linkedCreatorProfileId: 'cp_01HLINKED',
  connectionStatus: 'JOINED',
  connectionRequestId: 'ccr_01HJOINEDREQ',
};

function renderDiscovery() {
  return render(
    <MemoryRouter initialEntries={['/brand/discover']}>
      <CreatorDiscovery />
    </MemoryRouter>,
  );
}

async function openInstagramTab(user: ReturnType<typeof userEvent.setup>) {
  await user.click(await screen.findByRole('tab', { name: /instagram creators/i }));
}

describe('CreatorDiscovery — Instagram creators tab', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    creatorsSearch.mockResolvedValue({ creators: [], meta: { page: 1, limit: 20, hasMore: false } });
    externalConnectionRequests.mockResolvedValue([]);
  });

  it('renders the "Unverified with Influora" badge and connects via the dialog', async () => {
    externalList.mockResolvedValue({
      creators: [UNVERIFIED_CREATOR],
      meta: { page: 1, limit: 20, hasMore: false },
    });
    externalConnect.mockResolvedValue({
      id: 'ccr_1',
      externalCreatorId: UNVERIFIED_CREATOR.id,
      igUsername: UNVERIFIED_CREATOR.igUsername,
      displayName: null,
      avatarUrl: null,
      message: null,
      status: 'PENDING',
      createdAt: new Date().toISOString(),
      handledAt: null,
      updatedAt: new Date().toISOString(),
      linkedCreatorProfileId: null,
    });

    const user = userEvent.setup({ delay: null });
    renderDiscovery();
    await openInstagramTab(user);

    await waitFor(() => expect(externalList).toHaveBeenCalled());
    expect(await screen.findByText('Unverified with Influora')).toBeInTheDocument();

    await user.click(await screen.findByRole('button', { name: /connect this creator/i }));
    const dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getByRole('button', { name: /send request/i }));

    await waitFor(() => expect(externalConnect).toHaveBeenCalledTimes(1));
    expect(externalConnect.mock.calls[0][0]).toBe(UNVERIFIED_CREATOR.id);
  });

  it('renders "Verified with Influora" and a create-campaign link for a JOINED creator', async () => {
    externalList.mockResolvedValue({
      creators: [JOINED_CREATOR],
      meta: { page: 1, limit: 20, hasMore: false },
    });

    const user = userEvent.setup({ delay: null });
    renderDiscovery();
    await openInstagramTab(user);

    await waitFor(() => expect(externalList).toHaveBeenCalled());
    expect(await screen.findByText('Verified with Influora')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /connect this creator/i })).not.toBeInTheDocument();

    const link = await screen.findByRole('link', { name: /create campaign/i });
    // Q6.5 — the handoff carries the Instagram handle (and pending connection request id, if
    // any) alongside creatorId, so the campaign wizard's banner can show `@igUsername` instead of
    // resolving (and possibly showing a different) Influora username.
    expect(link).toHaveAttribute(
      'href',
      `/brand/campaigns/new?creatorId=${JOINED_CREATOR.linkedCreatorProfileId}&ig=${JOINED_CREATOR.igUsername}&crq=${JOINED_CREATOR.connectionRequestId}`,
    );
  });

  it('renders the unavailable state with no cards on a 503 from the list', async () => {
    const { ApiError } = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
    externalList.mockRejectedValue(
      new ApiError('INSTAGRAM_LOOKUP_UNAVAILABLE', "Instagram lookup isn't connected yet.", 503),
    );

    const user = userEvent.setup({ delay: null });
    renderDiscovery();
    await openInstagramTab(user);

    await waitFor(() => expect(externalList).toHaveBeenCalled());
    expect(await screen.findByTestId('instagram-unavailable')).toBeInTheDocument();
    expect(screen.queryByText(/@foodie.mumbai/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/@travel.with.raj/i)).not.toBeInTheDocument();
  });

  it('renders an error state with Retry on a 502 from the list — never the empty state (Q2.4)', async () => {
    const { ApiError } = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
    externalList.mockRejectedValue(
      new ApiError('SERVER_UNAVAILABLE', 'The server was briefly unavailable (502). Please try again in a moment.', 502),
    );

    const user = userEvent.setup({ delay: null });
    renderDiscovery();
    await openInstagramTab(user);

    await waitFor(() => expect(externalList).toHaveBeenCalledTimes(1));
    expect(await screen.findByTestId('instagram-error')).toBeInTheDocument();
    expect(screen.queryByTestId('instagram-empty')).not.toBeInTheDocument();
    expect(screen.queryByTestId('instagram-unavailable')).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /retry/i }));
    await waitFor(() => expect(externalList).toHaveBeenCalledTimes(2));
  });

  it('renders the error state (not empty) when the list fetch rejects with a network error, not an ApiError (Q2.4)', async () => {
    externalList.mockRejectedValue(new TypeError('Failed to fetch'));

    const user = userEvent.setup({ delay: null });
    renderDiscovery();
    await openInstagramTab(user);

    await waitFor(() => expect(externalList).toHaveBeenCalled());
    expect(await screen.findByTestId('instagram-error')).toBeInTheDocument();
    expect(screen.queryByTestId('instagram-empty')).not.toBeInTheDocument();
  });

  it('search box passes q= through to GET /creators/external (Q1.5)', async () => {
    externalList.mockResolvedValue({ creators: [], meta: { page: 1, limit: 20, hasMore: false } });

    const user = userEvent.setup({ delay: null });
    renderDiscovery();
    await openInstagramTab(user);
    await waitFor(() => expect(externalList).toHaveBeenCalledTimes(1));
    // Initial mount fetch carries no filters — every param below defaults away.
    expect(externalList.mock.calls[0][0]).toMatchObject({
      q: undefined,
      minFollowers: undefined,
      maxFollowers: undefined,
    });

    await user.type(screen.getByLabelText(/search instagram creators/i), 'foodie');
    await user.click(screen.getByRole('button', { name: /^search$/i }));

    await waitFor(() => expect(externalList).toHaveBeenCalledTimes(2));
    expect(externalList.mock.calls[1][0]).toMatchObject({ page: 1, q: 'foodie' });
  });

  it('the follower-range control exists and is wired to state, never hard-coded (Q1.5)', async () => {
    externalList.mockResolvedValue({ creators: [], meta: { page: 1, limit: 20, hasMore: false } });

    const user = userEvent.setup({ delay: null });
    renderDiscovery();
    await openInstagramTab(user);
    await waitFor(() => expect(externalList).toHaveBeenCalledTimes(1));

    // The Instagram tab is the only mounted tab content (Radix Tabs unmounts inactive panels),
    // so this is the follower-range control's own pair of thumbs — one per [min, max] bound.
    const sliders = screen.getAllByRole('slider');
    expect(sliders).toHaveLength(2);

    // Nudge the min-followers thumb up via keyboard (Radix's documented interaction) and confirm
    // the on-screen range label reacts — proves the control is live state, not a static display,
    // without depending on Radix's internal onValueCommit timing (covered functionally by the
    // search-triggered fetch above, which reads the SAME listFollowerRange state).
    sliders[0].focus();
    await user.keyboard('{ArrowRight}{ArrowRight}{ArrowRight}');

    await waitFor(() => expect(sliders[0]).not.toHaveAttribute('aria-valuenow', '0'));
  });
});
