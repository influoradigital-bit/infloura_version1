/**
 * Brand Dashboard greeting — F-0320 (persisted-value-no-consumer), closing the loop.
 *
 * F-0282 taught `persistBrandSession` to keep the backend's real `displayName` and exposed
 * `getBrandDisplayName()` to read it back out of localStorage — but nothing in the brand
 * login/register flow ever populated `useAuthStore().user`, so this greeting's
 * `user?.firstName || 'there'` rendered "Good morning, there" for every live brand session no
 * matter what the backend actually knew — the exact user-visible symptom F-0282 claimed to
 * close (see the F-0320 producer return for T-BRANDOPEN-0817).
 *
 * The wrong fix this guards against: a consumer that reads the persisted display name
 * SOMEWHERE — enough for a static "is `getBrandDisplayName` referenced anywhere?" grep to go
 * green — but still renders the placeholder, e.g.:
 *   getBrandDisplayName(); // referenced, never used
 *   return <>{greeting}, {user?.firstName || 'there'}</>;
 * Only an assertion on the RENDERED text catches that; a reference-exists check does not. This
 * suite renders the real `DashboardPage` component and reads the DOM, never the source text.
 *
 * Run: npx vitest run src/components/brand/dashboard/__tests__/dashboard-page-greeting.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { DashboardPage } from '../dashboard-page';

vi.mock('@/components/trendspark/TrendSparkNudgeCard', () => ({
  TrendSparkNudgeCard: () => null,
}));

vi.mock('@/hooks/use-toast', () => ({
  toast: vi.fn(),
  useToast: () => ({ toast: vi.fn() }),
}));

// F-0320 — `user` is the shared identity every brand page is supposed to read. Settable per
// test so the same render path can prove both the "real name present" and "honestly absent"
// cases, mirroring dashboard-page.test.tsx's (F-0246) harness.
let authUser: { firstName?: string; displayName?: string; email?: string } | null = null;
vi.mock('@/lib/store', () => ({
  useAuthStore: () => ({ user: authUser, logout: vi.fn() }),
}));

const actionsMock = vi.fn();
const pipelineMock = vi.fn();
const walletGetMock = vi.fn();

vi.mock('@/lib/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/api')>();
  return {
    ...actual,
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

describe('DashboardPage greeting — F-0320 renders the real signed-in person', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    actionsMock.mockResolvedValue([]);
    pipelineMock.mockResolvedValue([]);
    walletGetMock.mockResolvedValue({ availableBalance: 0, escrowLocked: 0, runwayDays: null });
    authUser = null;
  });

  it('greets by the real backend displayName when the store has one (live brand session)', async () => {
    authUser = { displayName: 'Priya Sharma', email: 'ops@realbrand.com' };
    renderDashboard();

    const heading = await screen.findByRole('heading', { name: /Priya/i });
    expect(heading).toBeInTheDocument();
    // The literal defect this closes: "Good morning, there" rendering for a session that DOES
    // carry a real name.
    expect(screen.queryByRole('heading', { name: /,\s*there$/i })).not.toBeInTheDocument();
  });

  it('a single-word displayName renders as-is (no name to split further)', async () => {
    authUser = { displayName: 'Glownaturals' };
    renderDashboard();

    expect(await screen.findByRole('heading', { name: /Glownaturals/i })).toBeInTheDocument();
  });

  it('falls back to the honest neutral "there" only when the store genuinely has no user — never a fabricated name', async () => {
    authUser = null;
    renderDashboard();

    expect(await screen.findByRole('heading', { name: /,\s*there$/i })).toBeInTheDocument();
  });
});
