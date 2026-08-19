/**
 * Brand Dashboard — F-0245 (loading-equals-error-equals-empty) and F-0246
 * (placeholder-identity-live) regression spec.
 *
 * F-0245: before this fix, `actionItems`/`wallet`/`pipeline` initialized straight to their
 * real-empty values with no loading/error state at all, so a still-loading dashboard, a
 * dashboard whose fetch failed, and a genuinely-empty dashboard all rendered the identical
 * "All caught up! No pending actions right now." screen. This file proves loading renders a
 * skeleton (not the empty copy), a rejected fetch renders a visible error with a retry (not
 * the empty copy), and only a *resolved* empty response earns the real empty state.
 *
 * F-0246: nothing in the brand login flow ever calls `useAuthStore().setUser`/`login()`, so
 * `user` is always `null` in a live brand session. Before this fix, `brand-layout.tsx`'s
 * sidebar fell back to the hardcoded `'Brand Account'` / `'brand@company.com'` strings
 * unconditionally whenever `user` was null — a fabricated identity rendered as if it were the
 * signed-in user's. This file proves that literal never renders, in either the GET
 * /workspaces/me success or failure path.
 *
 * HARNESS NOTES — mirrors src/pages/__tests__/brand-settings.test.tsx's `vi.mock('@/lib/api', ...)`
 * shape (isApiLive as a controllable mock fn, api.* as fine-grained vi.fn()s), and
 * src/components/feature/meera/__tests__/FundEscrowButton.test.tsx's use of `importOriginal`
 * to keep the rest of the real api.ts module intact.
 *
 * Run: npx vitest run src/components/brand/dashboard/__tests__/dashboard-page.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { DashboardPage } from '../dashboard-page';
import { BrandLayout } from '../../brand-layout';

const navigateMock = vi.fn();
vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom');
  return { ...actual, useNavigate: () => navigateMock };
});

const toastMock = vi.fn();
vi.mock('@/hooks/use-toast', () => ({
  useToast: () => ({ toast: (...a: unknown[]) => toastMock(...a) }),
  toast: (...a: unknown[]) => toastMock(...a),
}));

// Out of scope for this spec — stubbed so DashboardPage/BrandLayout render without pulling in
// their own network calls or animation timers.
vi.mock('@/components/trendspark/TrendSparkNudgeCard', () => ({
  TrendSparkNudgeCard: () => null,
}));
vi.mock('@/components/brand/WorkspaceVerificationBanner', () => ({
  WorkspaceVerificationBanner: () => null,
}));
vi.mock('@/components/brand/command-bar', () => ({ CommandBar: () => null }));
vi.mock('@/hooks/useNotifications', () => ({
  useNotifications: () => ({
    notifications: [],
    unreadCount: 0,
    loading: false,
    error: null,
    refresh: vi.fn(),
    markRead: vi.fn(),
    markAllRead: vi.fn(),
  }),
}));

// F-0246 — `user` is what every brand page reads for identity. It is `null` here by default,
// matching the real, permanent live-mode state (no brand code path ever calls
// useAuthStore().setUser/login()).
let authUser: { firstName?: string; displayName?: string; email?: string } | null = null;
vi.mock('@/lib/store', () => ({
  useAuthStore: () => ({ user: authUser, logout: vi.fn() }),
  useUIStore: () => ({
    mobileMenuOpen: false,
    toggleMobileMenu: vi.fn(),
    setMobileMenuOpen: vi.fn(),
    closeMobileMenu: vi.fn(),
  }),
}));

const apiLive = vi.fn();
const actionsMock = vi.fn();
const walletGetMock = vi.fn();
const pipelineMock = vi.fn();
const getMeMock = vi.fn();

vi.mock('@/lib/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/api')>();
  return {
    ...actual,
    isApiLive: () => apiLive(),
    api: {
      ...actual.api,
      dashboard: {
        ...actual.api.dashboard,
        actions: (...a: unknown[]) => actionsMock(...a),
        pipeline: (...a: unknown[]) => pipelineMock(...a),
      },
      wallet: {
        ...actual.api.wallet,
        get: (...a: unknown[]) => walletGetMock(...a),
      },
      workspaces: {
        ...actual.api.workspaces,
        getMe: (...a: unknown[]) => getMeMock(...a),
      },
    },
  };
});

function renderDashboard() {
  return render(
    <MemoryRouter initialEntries={['/brand/dashboard']}>
      <DashboardPage />
    </MemoryRouter>,
  );
}

function renderLayout() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/brand/dashboard']}>
        <BrandLayout>
          <div>page content</div>
        </BrandLayout>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('DashboardPage — F-0245 loading/error/empty are three distinct states', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    apiLive.mockReturnValue(true);
    authUser = null;
  });

  it('pending fetch renders skeletons, never the zero/empty copy', async () => {
    // Never resolve — keeps the component parked in the loading state.
    actionsMock.mockReturnValue(new Promise(() => {}));
    walletGetMock.mockReturnValue(new Promise(() => {}));
    pipelineMock.mockReturnValue(new Promise(() => {}));

    renderDashboard();

    expect(await screen.findByRole('status', { name: 'Loading pending actions' })).toBeInTheDocument();
    expect(screen.getByRole('status', { name: 'Loading pipeline' })).toBeInTheDocument();
    expect(screen.getByRole('status', { name: 'Loading wallet' })).toBeInTheDocument();

    // Must never assert the real-zero / all-caught-up copy while unresolved.
    expect(screen.queryByText('All caught up!')).not.toBeInTheDocument();
    expect(screen.queryByText('No pending actions right now.')).not.toBeInTheDocument();
    expect(screen.queryByText("You're all caught up.")).not.toBeInTheDocument();
    expect(screen.queryByText('No deals in your pipeline yet.')).not.toBeInTheDocument();
    expect(screen.queryByText(/pending$/)).not.toBeInTheDocument();
  });

  it('rejected fetch renders a visible error with retry, never the zero/empty copy', async () => {
    actionsMock.mockRejectedValue(new Error('network down'));
    walletGetMock.mockRejectedValue(new Error('network down'));
    pipelineMock.mockRejectedValue(new Error('network down'));

    renderDashboard();

    expect(await screen.findByText('Couldn’t load your pending actions.')).toBeInTheDocument();
    expect(screen.getByText('Couldn’t load your pipeline.')).toBeInTheDocument();
    expect(screen.getByText('Couldn’t load your wallet.')).toBeInTheDocument();

    // A retry affordance for every failed region.
    expect(screen.getAllByRole('button', { name: 'Retry' }).length).toBeGreaterThanOrEqual(3);

    // A failed fetch must never be presented as a confirmed real zero.
    expect(screen.queryByText('All caught up!')).not.toBeInTheDocument();
    expect(screen.queryByText('No pending actions right now.')).not.toBeInTheDocument();
    expect(screen.queryByText("You're all caught up.")).not.toBeInTheDocument();
    expect(screen.queryByText('No deals in your pipeline yet.')).not.toBeInTheDocument();
  });

  it('resolved-empty renders the real empty state', async () => {
    actionsMock.mockResolvedValue([]);
    walletGetMock.mockResolvedValue({ availableBalance: 0, escrowLocked: 0, pendingPayouts: 0, runwayDays: null });
    pipelineMock.mockResolvedValue([
      { stage: 'draft', count: 0 },
      { stage: 'active', count: 0 },
      { stage: 'completed', count: 0 },
    ]);

    renderDashboard();

    // F-0278: genuinely empty (no campaigns, no deals, zero wallet) now shows onboarding, not "all caught up"
    expect(await screen.findByText(/ready to launch your first campaign/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /create campaign/i })).toBeInTheDocument();

    // Should NOT show "all caught up" for a genuinely empty workspace
    expect(screen.queryByText('All caught up!')).not.toBeInTheDocument();

    // No leftover loading/error affordances once genuinely resolved-empty.
    expect(screen.queryByRole('status', { name: 'Loading pending actions' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Retry' })).not.toBeInTheDocument();
  });
});

describe('BrandLayout sidebar — F-0246 no fabricated identity in live mode', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    apiLive.mockReturnValue(true);
    authUser = null; // permanent live-mode state for brand — see note above
  });

  const FABRICATED_EMAIL = 'brand@company.com';
  const FABRICATED_NAME = 'Brand Account';

  it('GET /workspaces/me resolves: shows the real workspace identity, never the fabricated one', async () => {
    getMeMock.mockResolvedValue({
      id: 'ws_real',
      name: 'Real Brand Pvt Ltd',
      slug: 'real-brand',
      email: 'ops@realbrand.com',
      phone: null,
      industry: null,
      companySize: null,
      websiteUrl: null,
      logoUrl: null,
      verificationStatus: 'VERIFIED',
    });

    const user = userEvent.setup();
    renderLayout();

    await waitFor(() => expect(screen.getAllByText('Real Brand Pvt Ltd').length).toBeGreaterThan(0));

    // The real email lives in the account dropdown (DropdownMenuLabel), which Radix only
    // mounts once the trigger is opened — open it to reach the same identity text a user would.
    await user.click(screen.getAllByRole('button', { name: /Real Brand Pvt Ltd/ })[0]);
    await waitFor(() => expect(screen.getAllByText('ops@realbrand.com').length).toBeGreaterThan(0));

    expect(screen.queryByText(FABRICATED_EMAIL)).not.toBeInTheDocument();
    expect(screen.queryByText(FABRICATED_NAME)).not.toBeInTheDocument();
  });

  it('GET /workspaces/me rejects: degrades honestly, never renders the fabricated identity', async () => {
    getMeMock.mockRejectedValue(new Error('network down'));

    const user = userEvent.setup();
    renderLayout();

    // Honest neutral fallback — no name/email beats a wrong one.
    await waitFor(() => expect(screen.getAllByText('Workspace').length).toBeGreaterThan(0));

    await user.click(screen.getAllByRole('button', { name: /Workspace/ })[0]);
    await waitFor(() => expect(screen.getAllByText('No email on file').length).toBeGreaterThan(0));

    expect(screen.queryByText(FABRICATED_EMAIL)).not.toBeInTheDocument();
    expect(screen.queryByText(FABRICATED_NAME)).not.toBeInTheDocument();
  });
});
