import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { FundEscrowStatus, fundEscrowAnnouncement } from './FundEscrowStatus';

/**
 * F-0131 regression gate (no-live-region, money-path). The Fund Campaign Escrow card must expose a
 * polite live region so screen readers hear its state changes — above all the FundEscrowButton's
 * appearance after a campaign is picked, an otherwise-silent DOM insertion. If someone removes the
 * `role="status"` region, `getByRole('status')` throws and this suite fails.
 */
describe('FundEscrowStatus (F-0131)', () => {
  it('renders a polite role=status live region', () => {
    render(
      <FundEscrowStatus loading={false} selectableCount={2} totalCount={2} selectedId="" />,
    );
    const region = screen.getByRole('status');
    expect(region).toBeInTheDocument();
    expect(region).toHaveAttribute('aria-live', 'polite');
  });

  it('announces the FundEscrowButton becoming available once a campaign is selected', () => {
    render(
      <FundEscrowStatus
        loading={false}
        selectableCount={2}
        totalCount={2}
        selectedId="camp_1"
        selectedTitle="Summer Launch"
      />,
    );
    expect(screen.getByRole('status')).toHaveTextContent(
      'Ready to lock funds for Summer Launch. Continue to fund escrow.',
    );
  });

  it('announces loading, and the two distinct empty states', () => {
    expect(fundEscrowAnnouncement({ loading: true, selectableCount: 0, totalCount: 0, selectedId: '' })).toBe(
      'Loading your campaigns.',
    );
    expect(fundEscrowAnnouncement({ loading: false, selectableCount: 0, totalCount: 0, selectedId: '' })).toBe(
      'No active campaigns to fund right now.',
    );
    expect(fundEscrowAnnouncement({ loading: false, selectableCount: 0, totalCount: 3, selectedId: '' })).toBe(
      'Every active campaign already has funds locked.',
    );
  });

  it('prompts selection when campaigns exist but none is chosen yet', () => {
    expect(
      fundEscrowAnnouncement({ loading: false, selectableCount: 2, totalCount: 2, selectedId: '' }),
    ).toBe('Select a campaign to fund.');
  });

  it('falls back gracefully when the selected title cannot be resolved', () => {
    expect(
      fundEscrowAnnouncement({ loading: false, selectableCount: 1, totalCount: 1, selectedId: 'x' }),
    ).toContain('the selected campaign');
  });
});
