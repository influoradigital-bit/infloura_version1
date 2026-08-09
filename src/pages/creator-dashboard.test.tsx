/**
 * Creator Dashboard page — Kv3b (Kavya)
 * Smoke: greeting, wallet rollup, quick links in mock API mode.
 *
 * Run: npx vitest run src/pages/creator-dashboard.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import CreatorDashboardPage from './creator-dashboard';
import { api } from '@/lib/api';

vi.mock('@/components/creator/creator-layout', () => ({
  CreatorLayout: ({ children }: { children: React.ReactNode }) => (
    <div data-testid="creator-layout">{children}</div>
  ),
}));

vi.mock('@/components/motion', () => ({
  FadeUp: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  StaggerContainer: ({
    children,
    className,
  }: {
    children: React.ReactNode;
    className?: string;
  }) => <div className={className}>{children}</div>,
  StaggerItem: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}));

vi.mock('@/lib/store', () => ({
  useAuthStore: () => ({
    user: { displayName: 'Priya Sharma', firstName: 'Priya', role: 'creator' },
    logout: vi.fn(),
  }),
}));

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/creator/dashboard']}>
      <CreatorDashboardPage />
    </MemoryRouter>,
  );
}

describe('CreatorDashboardPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('greets the creator by first name after load', async () => {
    renderPage();

    expect(screen.getByTestId('creator-layout')).toBeInTheDocument();

    await waitFor(() => {
      expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(/Priya/i);
    });
  });

  it('shows available balance and active deals cards', async () => {
    renderPage();

    await waitFor(() => {
      expect(screen.getByText('Available balance')).toBeInTheDocument();
    });
    expect(screen.getByText('Active deals')).toBeInTheDocument();

    // Mock wallet creator availableBalance = 120000 → ₹1,20,000
    await waitFor(() => {
      expect(screen.getByText(/₹1,20,000/)).toBeInTheDocument();
    });
  });

  it('renders quick links to deals, campaigns, and wallet', async () => {
    renderPage();

    await waitFor(() => {
      expect(screen.getByRole('link', { name: /Deals/i })).toBeInTheDocument();
    });
    expect(screen.getByRole('link', { name: /Campaigns/i })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Wallet/i })).toBeInTheDocument();
  });

  /**
   * CR-51 regression pin. `loadDeliverablePendingCount` (creator-dashboard.tsx) used to call
   * `api.creatorDeliverables.listForDeal` once PER active deal — an N+1 waterfall of HTTP
   * round trips on every dashboard load. It now makes exactly one `listForDeals` call covering
   * every active deal id.
   *
   * This test would fail against the old code: the old implementation never referenced
   * `listForDeals` at all (so the "called once" assertion would see 0 calls) and called
   * `listForDeal` twice — once per mock-fixture active deal (`deal-active-1` `in_progress`,
   * `deal-active-2` `review`, per `mockDeals` in creator-deals.tsx).
   */
  it('CR-51: batches the pending-deliverable lookup into one listForDeals call, not one per deal', async () => {
    const listForDeals = vi.spyOn(api.creatorDeliverables, 'listForDeals').mockResolvedValue({});
    const listForDeal = vi.spyOn(api.creatorDeliverables, 'listForDeal');

    renderPage();

    await waitFor(() => {
      expect(listForDeals).toHaveBeenCalledTimes(1);
    });
    expect(listForDeals).toHaveBeenCalledWith(['deal-active-1', 'deal-active-2']);
    expect(listForDeal).not.toHaveBeenCalled();
  });
});
