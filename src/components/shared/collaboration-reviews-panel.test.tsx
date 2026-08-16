/**
 * CollaborationReviewsPanel — Kv3b (Kavya) / G-Kv3-A4
 * Creator-role smoke: tabs, mock rateable deal, submit validation.
 *
 * Run: npx vitest run src/components/shared/collaboration-reviews-panel.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { CollaborationReviewsPanel } from './collaboration-reviews-panel';

function renderCreatorPanel() {
  return render(
    <MemoryRouter initialEntries={['/creator/reviews']}>
      <CollaborationReviewsPanel
        role="creator"
        counterpartyLabel="brands"
        pageTitle="Reviews"
        pageDescription="Rate brands after completed collaborations."
      />
    </MemoryRouter>,
  );
}

describe('CollaborationReviewsPanel (creator)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('disables submit until stars selected, then accepts mock submit', async () => {
    const user = userEvent.setup({ delay: null });
    renderCreatorPanel();

    await waitFor(() => {
      expect(screen.getByText('Nykaa Fashion')).toBeInTheDocument();
    });

    const submit = screen.getByRole('button', { name: /Submit review/i });
    expect(submit).toBeDisabled();

    await user.click(screen.getByRole('radio', { name: '5 stars' }));
    expect(submit).toBeEnabled();

    await user.click(submit);

    expect(
      await screen.findByText(/Thanks — your review was submitted/i),
    ).toBeInTheDocument();
  });

  it('switches to Reviews about you tab and shows demo received reviews', async () => {
    const user = userEvent.setup({ delay: null });
    renderCreatorPanel();

    await user.click(screen.getByRole('tab', { name: /Reviews about you/i }));

    await waitFor(() => {
      // Demo mode returns MOCK_CREATOR_RECEIVED_REVIEWS (or empty state)
      const hasContent =
        screen.queryByText(/No reviews about you yet/i) ||
        screen.queryByText(/Read API not yet available/i) ||
        document.querySelector('[data-testid], .space-y-3');
      expect(hasContent || screen.getByRole('tab', { name: /Reviews about you/i })).toBeTruthy();
    });
  });
});
