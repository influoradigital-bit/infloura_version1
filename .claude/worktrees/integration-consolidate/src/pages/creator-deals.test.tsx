/**
 * Creator Deals page — Kv3b (Kavya)
 * Smoke: header, status chips, mock deal row in demo API mode.
 *
 * Run: npx vitest run src/pages/creator-deals.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import CreatorDealsPage from './creator-deals';

vi.mock('@/components/creator/creator-layout', () => ({
  CreatorLayout: ({ children }: { children: React.ReactNode }) => (
    <div data-testid="creator-layout">{children}</div>
  ),
}));

vi.mock('@/components/creator/hype-inbox-card', () => ({
  HypeInboxCard: () => <div data-testid="hype-inbox-card" />,
}));

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/creator/deals']}>
      <CreatorDealsPage />
    </MemoryRouter>,
  );
}

describe('CreatorDealsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders deals header and status filter chips', async () => {
    renderPage();

    expect(screen.getByTestId('creator-layout')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Deals' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /^All/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /^New/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /^Active/i })).toBeInTheDocument();
  });

  it('shows mock deal brand after load', async () => {
    renderPage();

    await waitFor(() => {
      expect(screen.getAllByText('Nykaa Fashion').length).toBeGreaterThanOrEqual(1);
    });
    expect(screen.getAllByText(/Summer Collection Launch/i).length).toBeGreaterThanOrEqual(1);
  });
});
