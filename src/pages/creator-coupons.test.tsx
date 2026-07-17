/**
 * Creator Coupons page — Kv3b (Kavya)
 * Smoke: header + mock coupon rows in demo API mode (G-Kv3 / §19).
 *
 * Run: npx vitest run src/pages/creator-coupons.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import CreatorCouponsPage from './creator-coupons';

vi.mock('@/components/creator/creator-layout', () => ({
  CreatorLayout: ({ children }: { children: React.ReactNode }) => (
    <div data-testid="creator-layout">{children}</div>
  ),
}));

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/creator/coupons']}>
      <CreatorCouponsPage />
    </MemoryRouter>,
  );
}

describe('CreatorCouponsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders page title inside creator layout', async () => {
    renderPage();

    expect(screen.getByTestId('creator-layout')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'My Coupons' })).toBeInTheDocument();
    expect(
      screen.getByText(/Coupon codes and tracking links assigned to you/i),
    ).toBeInTheDocument();
  });

  it('shows mock coupon codes after load', async () => {
    renderPage();

    await waitFor(() => {
      expect(screen.getByText('PRIYA20')).toBeInTheDocument();
    });
    expect(screen.getByText('Summer Collection Launch')).toBeInTheDocument();
    expect(screen.getByText('Nykaa Fashion')).toBeInTheDocument();
    expect(screen.getByText('BOAT15PRIYA')).toBeInTheDocument();
  });
});
