/**
 * CampaignsList — Edit control gated by campaign status (ACTIVE campaign not editable).
 *
 * WHY THIS FILE EXISTS
 * ---------------------
 * Backend now rejects `PATCH /campaigns/{id}` on an ACTIVE campaign with any non-status field
 * (409 `CAMPAIGN_ACTIVE_NOT_EDITABLE` — `CampaignValidator.ensureEditable`, Vikram's
 * 2026-08-14 SHARED_CONTEXT.md handoff). The Edit menu item here previously gated only on
 * workspace role, leaving an ACTIVE campaign's Edit control fully clickable — a dead-end that
 * would 409 once the edit form tried to save. This pins down the FE half: Edit is
 * disabled + captioned (TECH-STACK.md's "Disabled + captioned" state) for ACTIVE campaigns,
 * and unaffected for every other status a brand can still edit (DRAFT, PAUSED, etc).
 *
 * Run: npx vitest run src/components/brand/campaigns/campaigns-list-active-edit-gate.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, within } from '@testing-library/react';
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

function makeCampaign(id: string, status: Campaign['status']): Campaign {
  return {
    id,
    workspaceId: 'ws_1',
    title: `Campaign ${id}`,
    description: 'desc',
    objectives: [],
    status,
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

function renderList() {
  return render(
    <MemoryRouter initialEntries={['/brand/campaigns']}>
      <CampaignsList />
    </MemoryRouter>,
  );
}

async function openMenuFor(cardTitle: string) {
  const user = userEvent.setup({ delay: null });
  const titleEl = await screen.findByText(cardTitle);
  // The card is the closest ancestor that also contains the "..." trigger button.
  const card = titleEl.closest('.group') as HTMLElement;
  const trigger = within(card).getByRole('button', { name: '' }); // icon-only MoreHorizontal trigger
  await user.click(trigger);
  return user;
}

describe('CampaignsList — Edit gated by ACTIVE status', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    // myRole stays null (unknown) → canEdit is true by the existing role gate, isolating this
    // test to the NEW status gate rather than the pre-existing role gate.
    workspaceMembersList.mockResolvedValue([]);
  });

  it('disables Edit with a clear reason for an ACTIVE campaign', async () => {
    campaignsList.mockResolvedValue({
      campaigns: [makeCampaign('c1', 'ACTIVE')],
      meta: { page: 1, limit: 100, total: 1, hasMore: false },
    });
    renderList();

    await openMenuFor('Campaign c1');

    const editItem = await screen.findByRole('menuitem', { name: /edit/i });
    expect(editItem).toHaveAttribute('aria-disabled', 'true');
    expect(editItem).toHaveAttribute('title', 'Pause the campaign before editing its details');
    // Disabled item must not be a navigable link into the edit form.
    expect(editItem).not.toHaveAttribute('href');
  });

  it('leaves Edit fully working for a PAUSED campaign (no regression)', async () => {
    campaignsList.mockResolvedValue({
      campaigns: [makeCampaign('c2', 'PAUSED')],
      meta: { page: 1, limit: 100, total: 1, hasMore: false },
    });
    renderList();

    await openMenuFor('Campaign c2');

    const editItem = await screen.findByRole('menuitem', { name: /edit/i });
    expect(editItem).not.toHaveAttribute('aria-disabled', 'true');
    expect(editItem.closest('a')).toHaveAttribute('href', '/brand/campaigns/c2/edit');
  });

  it('leaves Edit fully working for a DRAFT campaign (no regression)', async () => {
    campaignsList.mockResolvedValue({
      campaigns: [makeCampaign('c3', 'DRAFT')],
      meta: { page: 1, limit: 100, total: 1, hasMore: false },
    });
    renderList();

    await openMenuFor('Campaign c3');

    const editItem = await screen.findByRole('menuitem', { name: /edit/i });
    expect(editItem).not.toHaveAttribute('aria-disabled', 'true');
    expect(editItem.closest('a')).toHaveAttribute('href', '/brand/campaigns/c3/edit');
  });

  it('Pause/Resume status-change control for an ACTIVE campaign is untouched by the Edit gate', async () => {
    campaignsList.mockResolvedValue({
      campaigns: [makeCampaign('c4', 'ACTIVE')],
      meta: { page: 1, limit: 100, total: 1, hasMore: false },
    });
    renderList();

    await openMenuFor('Campaign c4');

    // Pause Campaign remains a live, enabled action — only Edit is gated.
    const pauseItem = await screen.findByRole('menuitem', { name: /pause campaign/i });
    expect(pauseItem).not.toHaveAttribute('aria-disabled', 'true');
  });
});
