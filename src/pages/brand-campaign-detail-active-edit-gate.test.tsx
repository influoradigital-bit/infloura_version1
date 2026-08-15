/**
 * BrandCampaignDetailPage — header Edit button gated by campaign status (ACTIVE campaign not
 * editable).
 *
 * WHY THIS FILE EXISTS
 * ---------------------
 * Backend now rejects `PATCH /campaigns/{id}` on an ACTIVE campaign with any non-status field
 * (409 `CAMPAIGN_ACTIVE_NOT_EDITABLE` — `CampaignValidator.ensureEditable`, Vikram's
 * 2026-08-14 SHARED_CONTEXT.md handoff). This page's header Edit button previously hid only for
 * COMPLETED, leaving it live (and a guaranteed 409 on save) for ACTIVE campaigns. Fixed: Edit is
 * disabled with a Tooltip reason for ACTIVE, unchanged (working) for every other still-editable
 * status.
 *
 * Run: npx vitest run src/pages/brand-campaign-detail-active-edit-gate.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import BrandCampaignDetailPage from './brand-campaign-detail';
import type { Campaign } from '@/lib/types';

vi.mock('@/hooks/use-toast', () => ({
  useToast: () => ({ toast: vi.fn() }),
}));

const campaignsGet = vi.fn();
const dealsList = vi.fn();
const campaignsAnalytics = vi.fn();

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
        analytics: (...a: unknown[]) => campaignsAnalytics(...a),
      },
      deals: { ...actual.api.deals, list: (...a: unknown[]) => dealsList(...a) },
    },
  };
});

function makeCampaign(status: Campaign['status']): Campaign {
  return {
    id: 'camp_1',
    workspaceId: 'ws_1',
    title: 'Diwali Skincare Reels',
    description: 'Promote our new serum line',
    objectives: ['Brand awareness'],
    status,
    budget: { min: 10000, max: 20000, currency: 'INR' },
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

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/brand/campaigns/camp_1']}>
      <Routes>
        <Route path="/brand/campaigns/:id" element={<BrandCampaignDetailPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('BrandCampaignDetailPage — header Edit gated by ACTIVE status', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    dealsList.mockResolvedValue([]);
    campaignsAnalytics.mockResolvedValue(null);
  });

  it('disables Edit with a clear reason for an ACTIVE campaign', async () => {
    campaignsGet.mockResolvedValue(makeCampaign('ACTIVE'));
    renderPage();

    const editButton = await screen.findByRole('button', { name: /edit/i });
    expect(editButton).toBeDisabled();
    expect(editButton).toHaveAttribute('title', 'Pause the campaign before editing its details');
  });

  it('leaves Edit fully working (enabled, navigable) for a PAUSED campaign', async () => {
    campaignsGet.mockResolvedValue(makeCampaign('PAUSED'));
    renderPage();

    const editButton = await screen.findByRole('button', { name: /edit/i });
    expect(editButton).toBeEnabled();
  });

  it('leaves Edit fully working (enabled, navigable) for a DRAFT campaign', async () => {
    campaignsGet.mockResolvedValue(makeCampaign('DRAFT'));
    renderPage();

    const editButton = await screen.findByRole('button', { name: /edit/i });
    expect(editButton).toBeEnabled();
  });

  it('still hides Edit entirely for a COMPLETED campaign (pre-existing behavior, unchanged)', async () => {
    campaignsGet.mockResolvedValue(makeCampaign('COMPLETED'));
    renderPage();

    // Wait for the page to finish loading before asserting absence.
    await screen.findByText('Diwali Skincare Reels');
    expect(screen.queryByRole('button', { name: /^edit$/i })).not.toBeInTheDocument();
  });

  it('Pause/Resume status-change action for an ACTIVE campaign is untouched by the Edit gate', async () => {
    campaignsGet.mockResolvedValue(makeCampaign('ACTIVE'));
    renderPage();

    await screen.findByText('Diwali Skincare Reels');
    const user = userEvent.setup();
    // Open the "..." header action menu (separate from the Edit button).
    const moreButtons = screen.getAllByRole('button', { name: '' });
    await user.click(moreButtons[moreButtons.length - 1]);
    const pauseItem = await screen.findByText(/pause campaign/i);
    expect(pauseItem.closest('[role="menuitem"]')).not.toHaveAttribute('aria-disabled', 'true');
  });
});
