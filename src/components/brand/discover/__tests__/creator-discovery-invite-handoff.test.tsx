/**
 * F-0257 — dropped-handoff-context.
 * ----------------------------------------------------------------------------
 * The invite dialog's "Create New Campaign" option told the brand: "You will be redirected to
 * create a new campaign with {creator} pre-selected." That promise was never true —
 * `handleInvite` navigates to `/brand/campaigns/new?creator=<id>`, but
 * `src/pages/brand-new-campaign.tsx` (owned by another producer, out of scope for this ticket)
 * never reads a `creator` search param, `useSearchParams`, or anything else off that URL. The
 * creator the brand just picked is silently dropped on arrival — the campaign wizard starts
 * exactly as if they had clicked "New Campaign" from a blank slate.
 *
 * Because the destination file is out of scope here (do not edit
 * src/pages/brand-new-campaign.tsx per the task grant), the fix on THIS side is to stop
 * promising a pre-selection the app cannot deliver, rather than paper over it with a param the
 * destination still won't read. `navigate` still gets called with `?creator=` — harmless, and
 * ready for whoever fixes the destination — but the copy no longer claims the creator carries
 * through.
 *
 * Two guarantees:
 *   A. The "Create New Campaign" explainer text does not claim the creator will be pre-selected
 *      (no "pre-selected" / "pre-select" language) — a promise-scan, not a fixed string, so a
 *      copy edit that reintroduces the claim in different words still fails this test as long as
 *      it uses that vocabulary; a genuinely new promise would need a genuinely new fix.
 *   B. Confirming "Create Campaign" still navigates to /brand/campaigns/new (not silently
 *      disabled) — the flow keeps working, only the false promise is gone.
 *
 * Run: npx vitest run src/components/brand/discover/__tests__/creator-discovery-invite-handoff.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { CreatorDiscovery } from '../creator-discovery';

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
const campaignsList = vi.fn();

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    isApiLive: () => true,
    api: {
      creators: {
        search: (...a: unknown[]) => creatorsSearch(...a),
        invite: vi.fn(),
        toggleSaved: vi.fn().mockResolvedValue({ saved: true }),
        featured: vi.fn().mockResolvedValue({ featured: [] }),
      },
      deals: { create: vi.fn() },
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

async function openModalAndPickCreateNew(user: ReturnType<typeof userEvent.setup>) {
  await waitFor(() => expect(creatorsSearch).toHaveBeenCalled());
  await user.click(await screen.findByRole('button', { name: /^Invite$/i }));
  const dialog = await screen.findByRole('dialog');
  await user.click(within(dialog).getByRole('combobox'));
  await user.click(await screen.findByRole('option', { name: /Create New Campaign/i }));
  return dialog;
}

describe('F-0257 — invite → create-campaign handoff no longer over-promises', () => {
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
  });

  it('does not claim the creator will be pre-selected on the new-campaign page', async () => {
    const user = userEvent.setup({ delay: null });
    renderDiscovery();
    const dialog = await openModalAndPickCreateNew(user);

    // Guarantee A — no "pre-select(ed)" promise anywhere in the explainer.
    expect(within(dialog).queryByText(/pre-select/i)).not.toBeInTheDocument();
  });

  it('still navigates to the campaign wizard when the brand confirms', async () => {
    const user = userEvent.setup({ delay: null });
    renderDiscovery();
    const dialog = await openModalAndPickCreateNew(user);

    await user.click(within(dialog).getByRole('button', { name: /Create Campaign/i }));

    await waitFor(() => expect(navigateMock).toHaveBeenCalled());
    const target = String(navigateMock.mock.calls.at(-1)?.[0]);
    expect(target).toContain('/brand/campaigns/new');
  });
});
