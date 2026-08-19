/**
 * F-0278 regression test — empty brand dashboard must show onboarding, not "all caught up".
 *
 * Before the fix, a brand with zero campaigns, zero deals, and an empty wallet saw the subtitle
 * "You're all caught up" and a green checkmark with "All caught up! No pending actions right now."
 * The reassurance was inverted: they had done nothing yet and needed CTAs to create a campaign
 * and fund their wallet.
 *
 * The fix distinguishes three cases:
 * (a) genuinely new — no campaigns, no deals, nothing ever → onboarding state with CTAs
 * (b) established brand with nothing pending right now → "All caught up" (correct)
 * (c) data failed to load → error state (never "all caught up" or "you are new")
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { DashboardPage } from '../dashboard-page';
import { api } from '@/lib/api';

vi.mock('@/lib/api', () => ({
  api: {
    dashboard: {
      actions: vi.fn(),
      pipeline: vi.fn(),
    },
    wallet: {
      get: vi.fn(),
    },
    // Read by BrandFirstRunChecklist. Stubbed explicitly rather than left off: the checklist
    // survives an absent client (it degrades to "step undeterminable"), so omitting this would
    // silently exercise that degraded path instead of the one this file is about.
    campaigns: {
      list: vi.fn().mockResolvedValue({ campaigns: [], meta: { page: 1, limit: 1, total: 0, hasMore: false } }),
    },
  },
  ApiError: class ApiError extends Error {},
}));

vi.mock('@/lib/store', () => ({
  useAuthStore: () => ({
    user: { displayName: 'Test Brand', firstName: 'Test' },
  }),
}));

// Stub out components that pull in their own network calls
vi.mock('@/components/trendspark/TrendSparkNudgeCard', () => ({
  TrendSparkNudgeCard: () => null,
}));
vi.mock('@/components/brand/WorkspaceVerificationBanner', () => ({
  WorkspaceVerificationBanner: () => null,
}));

function renderDashboard() {
  return render(
    <MemoryRouter initialEntries={['/brand/dashboard']}>
      <DashboardPage />
    </MemoryRouter>
  );
}

describe('F-0278 — Dashboard empty state must distinguish new vs established', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('shows onboarding state for genuinely empty workspace (no campaigns, no deals, zero wallet)', async () => {
    // Mock: all endpoints return empty/zero data
    vi.mocked(api.dashboard.actions).mockResolvedValue([]);
    vi.mocked(api.dashboard.pipeline).mockResolvedValue([
      { stage: 'draft', count: 0 },
      { stage: 'active', count: 0 },
      { stage: 'completed', count: 0 },
    ]);
    vi.mocked(api.wallet.get).mockResolvedValue({
      availableBalance: 0,
      escrowLocked: 0,
      pendingPayouts: 0,
      runwayDays: null,
    });

    renderDashboard();

    // Wait for data to load
    await waitFor(() => {
      expect(screen.queryByText(/loading/i)).not.toBeInTheDocument();
    });

    // Should show onboarding message, not "all caught up"
    expect(screen.getByText(/your brand workspace is ready/i)).toBeInTheDocument();
    expect(screen.queryByText(/you're all caught up/i)).not.toBeInTheDocument();

    // Should show onboarding CTA in the actions card
    expect(screen.getByText(/ready to launch your first campaign/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /create campaign/i })).toBeInTheDocument();
  });

  it('shows "all caught up" for established brand with no pending actions', async () => {
    // Mock: no pending actions, but wallet has balance (established brand)
    vi.mocked(api.dashboard.actions).mockResolvedValue([]);
    vi.mocked(api.dashboard.pipeline).mockResolvedValue([
      { stage: 'draft', count: 1 }, // Has at least one campaign
      { stage: 'active', count: 0 },
      { stage: 'completed', count: 2 },
    ]);
    vi.mocked(api.wallet.get).mockResolvedValue({
      availableBalance: 50000,
      escrowLocked: 0,
      pendingPayouts: 0,
      runwayDays: 45,
    });

    renderDashboard();

    await waitFor(() => {
      expect(screen.queryByText(/loading/i)).not.toBeInTheDocument();
    });

    // Established brand with nothing pending sees "all caught up"
    expect(screen.getByText(/you're all caught up/i)).toBeInTheDocument();
    expect(screen.getByText('All caught up!')).toBeInTheDocument();
    expect(screen.getByText('No pending actions right now.')).toBeInTheDocument();

    // Should NOT show onboarding
    expect(screen.queryByText(/ready to launch your first campaign/i)).not.toBeInTheDocument();
  });

  it('shows error state when data fails to load (never "all caught up" or onboarding)', async () => {
    // Mock: actions endpoint fails
    vi.mocked(api.dashboard.actions).mockRejectedValue(new Error('Network error'));
    vi.mocked(api.dashboard.pipeline).mockResolvedValue([]);
    vi.mocked(api.wallet.get).mockResolvedValue({
      availableBalance: 0,
      escrowLocked: 0,
      pendingPayouts: 0,
      runwayDays: null,
    });

    renderDashboard();

    await waitFor(() => {
      expect(screen.queryByText(/loading/i)).not.toBeInTheDocument();
    });

    // Should show error message
    expect(screen.getByText(/some figures may be out of date/i)).toBeInTheDocument();

    // Should NOT show "all caught up" or onboarding when there's an error
    expect(screen.queryByText(/you're all caught up/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/ready to launch your first campaign/i)).not.toBeInTheDocument();
  });

  it('never shows "all caught up" while still loading', async () => {
    vi.mocked(api.dashboard.actions).mockImplementation(
      () => new Promise((resolve) => setTimeout(() => resolve([]), 1000))
    );
    vi.mocked(api.dashboard.pipeline).mockResolvedValue([]);
    vi.mocked(api.wallet.get).mockResolvedValue({
      availableBalance: 0,
      escrowLocked: 0,
      pendingPayouts: 0,
      runwayDays: null,
    });

    renderDashboard();

    // While loading, should show loading message, never "all caught up"
    expect(screen.getByText(/loading your latest activity/i)).toBeInTheDocument();
    expect(screen.queryByText(/you're all caught up/i)).not.toBeInTheDocument();
  });
});
