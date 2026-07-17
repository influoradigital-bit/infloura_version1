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
});
