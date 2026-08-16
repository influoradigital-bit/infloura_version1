/**
 * D-2 fix — campaign list pagination.
 *
 * `campaigns.list` used to call `http.request` (not `http.requestWithMeta`) against the
 * `ApiResponse.ok(items, meta)` envelope, so `envelope.meta` was silently discarded. The list
 * component requested `limit: 100` (the server's hard ceiling — CampaignService.list) with zero
 * pagination UI, so a brand with 101+ campaigns lost everything past #100 with no warning, no
 * "load more", and no total count — unreachable except by typing the URL directly.
 *
 * This pins down the fix: `campaigns.list` now returns `{ campaigns, meta }` (mirroring
 * `creators.search`'s `requestWithMeta` pattern), and the list component surfaces `meta.hasMore`
 * as a "Load more" control and `meta.total` as a visible count.
 *
 * Run: npx vitest run src/components/brand/campaigns/campaigns-list.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { CampaignsList } from './campaigns-list';
import type { Campaign } from '@/lib/types';

const campaignsList = vi.fn();
const workspaceMembersList = vi.fn();

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    isApiLive: () => true,
    api: {
      ...actual.api,
      campaigns: { ...actual.api.campaigns, list: (...a: unknown[]) => campaignsList(...a) },
      workspaceMembers: {
        ...actual.api.workspaceMembers,
        list: (...a: unknown[]) => workspaceMembersList(...a),
      },
    },
  };
});

function makeCampaign(id: string): Campaign {
  return {
    id,
    workspaceId: 'ws_1',
    title: `Campaign ${id}`,
    description: 'desc',
    objectives: [],
    status: 'ACTIVE',
    budget: { min: 1000, max: 2000, currency: 'INR' },
    timeline: { startDate: new Date('2026-01-01'), endDate: new Date('2026-02-01') },
    platforms: ['INSTAGRAM'],
    contentTypes: ['POST'],
    isPrivate: false,
    maxCollaborators: 5,
    createdBy: 'user_1',
    createdAt: new Date('2026-01-01'),
    updatedAt: new Date('2026-01-01'),
  } as Campaign;
}

// 101 total campaigns server-side, hard-capped at 100/page — page 1 is full and `hasMore: true`.
const PAGE_1 = Array.from({ length: 100 }, (_, i) => makeCampaign(`c${i + 1}`));
const PAGE_2 = [makeCampaign('c101')];

function renderList() {
  return render(
    <MemoryRouter initialEntries={['/brand/campaigns']}>
      <CampaignsList />
    </MemoryRouter>,
  );
}

describe('CampaignsList — server-side pagination (D-2)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    workspaceMembersList.mockResolvedValue([]);
    campaignsList.mockImplementation(async ({ page }: { page?: number } = {}) => {
      if (page === 2) {
        return { campaigns: PAGE_2, meta: { page: 2, limit: 100, total: 101, hasMore: false } };
      }
      return { campaigns: PAGE_1, meta: { page: 1, limit: 100, total: 101, hasMore: true } };
    });
  });

  it('requests page 1 at the 100-row server ceiling, shows the total count, and renders a Load more control instead of silently truncating', async () => {
    renderList();

    await waitFor(() => expect(campaignsList).toHaveBeenCalledWith(
      expect.objectContaining({ page: 1, limit: 100 }),
    ));

    // Total count is now visible (was previously nowhere on screen).
    expect(await screen.findByText(/Showing 100 of 101/i)).toBeInTheDocument();

    // The "Total Campaigns" stat card reflects the server total (101), not just the loaded 100.
    const statCard = screen.getByText('Total Campaigns').closest('div');
    expect(within(statCard!.parentElement!).getByText('101')).toBeInTheDocument();

    // A pager control exists — the defect was ZERO pagination UI.
    const loadMoreBtn = screen.getByRole('button', { name: /load more/i });
    expect(loadMoreBtn).toBeInTheDocument();

    // Campaign #101 (past the old hard truncation point) is not yet on screen.
    expect(screen.queryByText('Campaign c101')).not.toBeInTheDocument();
  });

  it('Load more fetches page 2 and appends the previously-unreachable 101st campaign', async () => {
    const user = userEvent.setup({ delay: null });
    renderList();

    const loadMoreBtn = await screen.findByRole('button', { name: /load more/i });
    await user.click(loadMoreBtn);

    await waitFor(() => expect(campaignsList).toHaveBeenCalledWith(
      expect.objectContaining({ page: 2, limit: 100 }),
    ));

    // Now reachable — this is exactly what D-2 says was previously impossible short of the URL.
    expect(await screen.findByText('Campaign c101')).toBeInTheDocument();
    expect(await screen.findByText(/Showing 101 of 101/i)).toBeInTheDocument();

    // hasMore is now false — the control retires itself.
    await waitFor(() =>
      expect(screen.queryByRole('button', { name: /load more/i })).not.toBeInTheDocument(),
    );
  });
});
