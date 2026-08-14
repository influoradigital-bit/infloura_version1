/**
 * CreatorCampaignDetailPage — a slow response for a previous campaign no longer overwrites a
 * newer one (CR-11 static-sweep hardening).
 *
 * WHY THIS FILE EXISTS
 * ---------------------
 * This route (`/creator/campaigns/:id`) does NOT unmount/remount when only the `id` param
 * changes — React Router reuses the same component instance. `loadCampaign` had no staleness
 * guard at all, so navigating from campaign A's detail page to campaign B's before A's slower
 * `GET /creator/campaigns/:id` response landed could silently overwrite `campaign` with A's
 * data while the URL — and every derived affordance — said B. Fixed with a monotonic request
 * token, matching the `messagesRequestRef` pattern already established in creator-chat.tsx
 * (CR-20): "latest request wins."
 *
 * NOTE: this hardening does not reproduce or confirm CR-11's actual (still-unreproduced)
 * white-screen crash — see that ticket's tracker entry. This is a real, separate data-integrity
 * race, fixed and pinned on its own merits.
 *
 * Run: npx vitest run src/pages/creator-campaign-detail-stale-response.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Routes, Route, Link } from 'react-router-dom';
import CreatorCampaignDetailPage from './creator-campaign-detail';

vi.mock('@/hooks/use-toast', () => ({
  useToast: () => ({ toast: vi.fn() }),
}));

class MockIntersectionObserver {
  observe() {}
  unobserve() {}
  disconnect() {}
}
// @ts-expect-error — jsdom has no IntersectionObserver global.
global.IntersectionObserver = MockIntersectionObserver;

vi.mock('@/components/creator/creator-layout', () => ({
  CreatorLayout: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));

const campaignGet = vi.fn();

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    api: {
      creatorCampaigns: {
        get: (...a: unknown[]) => campaignGet(...a),
        apply: vi.fn(),
      },
    },
  };
});

const BASE_CAMPAIGN = {
  platforms: ['INSTAGRAM'],
  requirements: [],
  applicationDeadline: null,
  startDate: null,
  endDate: null,
  maxCollaborators: null,
  createdAt: '2026-07-01T00:00:00Z',
  objectives: [],
  contentTypes: [],
  hashtags: [],
  brandGuidelines: null,
  applicationStatus: null,
};

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/creator/campaigns/camp_1']}>
      <Link to="/creator/campaigns/camp_2">Go to campaign 2</Link>
      <Routes>
        <Route path="/creator/campaigns/:id" element={<CreatorCampaignDetailPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('CreatorCampaignDetailPage — stale-response race (CR-11 static-sweep hardening)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('a slow campaign-A response landing after navigating to campaign B does not overwrite B', async () => {
    const user = userEvent.setup();

    // camp_1's request never resolves during this test until we manually resolve it, late.
    let resolveCamp1: (value: unknown) => void;
    const camp1Promise = new Promise((resolve) => {
      resolveCamp1 = resolve;
    });
    campaignGet.mockImplementation((id: string) => {
      if (id === 'camp_1') return camp1Promise;
      if (id === 'camp_2') {
        return Promise.resolve({
          ...BASE_CAMPAIGN,
          id: 'camp_2',
          title: 'Campaign B — already loaded',
          description: 'The real, current campaign',
        });
      }
      throw new Error(`unexpected id ${id}`);
    });

    renderPage();

    // camp_1's request is in flight (never resolves yet) — navigate away before it lands.
    await user.click(screen.getByText('Go to campaign 2'));

    // Campaign B loads and renders correctly.
    await waitFor(() => expect(screen.getByText('Campaign B — already loaded')).toBeInTheDocument());

    // NOW the stale camp_1 response lands, long after the user moved on.
    resolveCamp1!({
      ...BASE_CAMPAIGN,
      id: 'camp_1',
      title: 'Campaign A — stale, must not appear',
      description: 'This should never render once B is showing',
    });

    // Give the stale promise's .then chain a tick to run (it should be a no-op).
    await new Promise((r) => setTimeout(r, 0));

    // Campaign B's content must still be showing — not overwritten by the late camp_1 response.
    expect(screen.getByText('Campaign B — already loaded')).toBeInTheDocument();
    expect(screen.queryByText('Campaign A — stale, must not appear')).not.toBeInTheDocument();
  });

  it('a normal single load (no race) still works', async () => {
    campaignGet.mockResolvedValue({
      ...BASE_CAMPAIGN,
      id: 'camp_1',
      title: 'Solo Campaign',
      description: 'No race here',
    });

    renderPage();

    expect(await screen.findByText('Solo Campaign')).toBeInTheDocument();
  });
});
