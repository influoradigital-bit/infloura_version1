/**
 * Creator Wallet page — Kv3b (Kavya)
 * Covers G-Kv3-A3: platform fee label after mock fetch (L-31-3).
 *
 * Run: npx vitest run src/pages/creator-wallet.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import CreatorWalletPage from './creator-wallet';

vi.mock('@/components/creator/creator-layout', () => ({
  CreatorLayout: ({ children }: { children: React.ReactNode }) => (
    <div data-testid="creator-layout">{children}</div>
  ),
}));

vi.mock('@/lib/store', () => ({
  useAuthStore: () => ({
    user: { displayName: 'Priya Sharma', firstName: 'Priya', role: 'creator' },
    logout: vi.fn(),
  }),
}));

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/creator/wallet']}>
      <CreatorWalletPage />
    </MemoryRouter>,
  );
}

describe('CreatorWalletPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders wallet header and withdraw CTA', async () => {
    renderPage();

    expect(screen.getByTestId('creator-layout')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Wallet' })).toBeInTheDocument();
    expect(screen.getByText(/Track your earnings and payouts/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Withdraw/i })).toBeInTheDocument();
  });

  it('shows platform fee percent after mock fetch (G-Kv3-A3)', async () => {
    renderPage();

    await waitFor(() => {
      expect(screen.getByText(/Platform fee:\s*15%/i)).toBeInTheDocument();
    });
    expect(
      screen.getByText(/Deducted when campaign earnings are released from escrow/i),
    ).toBeInTheDocument();
  });

  it('renders payouts / history / tax tabs in demo mode', async () => {
    renderPage();

    await waitFor(() => {
      expect(screen.getByRole('tab', { name: 'Payouts' })).toBeInTheDocument();
    });
    expect(screen.getByRole('tab', { name: 'History' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Tax Docs' })).toBeInTheDocument();
  });
});
