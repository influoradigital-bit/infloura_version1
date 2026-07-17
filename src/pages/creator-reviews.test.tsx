/**
 * Creator Reviews page — Kv3b (Kavya)
 * Smoke + panel wiring for Task #33 A4 / G-Kv3-A4.
 *
 * Run: npx vitest run src/pages/creator-reviews.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import CreatorReviewsPage from './creator-reviews';

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
    <MemoryRouter initialEntries={['/creator/reviews']}>
      <CreatorReviewsPage />
    </MemoryRouter>,
  );
}

describe('CreatorReviewsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders page title and description inside creator layout', async () => {
    renderPage();

    expect(screen.getByTestId('creator-layout')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Reviews' })).toBeInTheDocument();
    expect(
      screen.getByText(/Rate brands after completed collaborations/i),
    ).toBeInTheDocument();
  });

  it('shows Rate brands tab and mock rateable deal in demo mode', async () => {
    renderPage();

    expect(screen.getByRole('tab', { name: /Rate brands/i })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: /Reviews about you/i })).toBeInTheDocument();

    await waitFor(() => {
      expect(screen.getByText('Nykaa Fashion')).toBeInTheDocument();
    });
    expect(screen.getByText('Winter Collection')).toBeInTheDocument();
  });
});
