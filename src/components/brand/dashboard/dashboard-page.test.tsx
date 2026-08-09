/**
 * Brand Dashboard — D-1 (BrandF.md §5) regression test.
 *
 * A funded-but-dormant wallet (no spend in the trailing window) must render as
 * "healthy" / "—" runway / "Manage wallet", never a fabricated "0d runway" /
 * "Critical" / "Recharge now" alarm. Per WalletService#computeRunwayDays javadoc
 * and WalletSummaryResponse (@JsonInclude(NON_NULL)), `runwayDays` is `null` — not
 * present in the JSON at all — for a dormant wallet; the frontend must treat that
 * as healthy, not coerce it to 0.
 *
 * Run: npx vitest run src/components/brand/dashboard/dashboard-page.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { DashboardPage } from './dashboard-page';

vi.mock('@/lib/store', () => ({
  useAuthStore: () => ({
    user: { firstName: 'Ananya', role: 'brand' },
  }),
}));

vi.mock('@/components/trendspark/TrendSparkNudgeCard', () => ({
  TrendSparkNudgeCard: () => null,
}));

const actionsMock = vi.fn();
const pipelineMock = vi.fn();
const walletGetMock = vi.fn();

vi.mock('@/lib/api', () => ({
  api: {
    dashboard: {
      actions: (...args: unknown[]) => actionsMock(...args),
      pipeline: (...args: unknown[]) => pipelineMock(...args),
    },
    wallet: {
      get: (...args: unknown[]) => walletGetMock(...args),
    },
  },
  ApiError: class ApiError extends Error {},
}));

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/brand/dashboard']}>
      <DashboardPage />
    </MemoryRouter>,
  );
}

describe('DashboardPage — wallet runway (D-1)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    actionsMock.mockResolvedValue([]);
    pipelineMock.mockResolvedValue([]);
  });

  it('renders a dormant-but-funded wallet (runwayDays omitted/null) as healthy, not critical', async () => {
    // Mirrors the real backend response shape: @JsonInclude(NON_NULL) means
    // `runwayDays` is simply absent from the JSON, which JSON.parse/fetch surfaces
    // as `undefined` on the object — the actual dormant-wallet contract, not just
    // an explicit `null` in a hand-written mock.
    walletGetMock.mockResolvedValue({
      availableBalance: 500000,
      escrowLocked: 0,
      pendingPayouts: 0,
    });

    renderPage();

    await waitFor(() => {
      expect(screen.getByText('Healthy')).toBeInTheDocument();
    });

    expect(screen.queryByText('Critical')).not.toBeInTheDocument();
    expect(screen.getByText('—')).toBeInTheDocument();
    expect(screen.queryByText(/0d runway/)).not.toBeInTheDocument();

    expect(screen.getByRole('button', { name: 'Manage wallet' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Recharge now' })).not.toBeInTheDocument();
  });

  it('still renders a genuinely low-runway wallet (runwayDays: 5) as critical', async () => {
    walletGetMock.mockResolvedValue({
      availableBalance: 5000,
      escrowLocked: 0,
      pendingPayouts: 0,
      runwayDays: 5,
    });

    renderPage();

    await waitFor(() => {
      expect(screen.getByText('Critical')).toBeInTheDocument();
    });

    expect(screen.queryByText('Healthy')).not.toBeInTheDocument();
    expect(screen.getByText('5d runway')).toBeInTheDocument();

    expect(screen.getByRole('button', { name: 'Recharge now' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Manage wallet' })).not.toBeInTheDocument();
  });
});
