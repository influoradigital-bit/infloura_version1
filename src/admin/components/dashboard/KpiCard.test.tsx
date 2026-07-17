/**
 * INFLUORA ADMIN PANEL — KpiCard Component Tests
 * Owner: Ananya (Frontend)
 *
 * Regression guard for the null-vs-zero WoW change bug: the backend now
 * sends `null` for gmvChange/revenueChange/activeCampaignsChange when no
 * `kpi_daily_snapshot` baseline exists yet (Priya + Rohan ruling,
 * 2026-07-15). `null` must render as an honest "—" with no trend arrow and
 * no positive/negative color. A genuine `0` must still render as a real
 * flat "0%" — the two states must never collapse into each other.
 *
 * This specifically guards against `change >= 0` (or any other
 * truthiness/relational check), because `null >= 0` is `true` in
 * JavaScript — a naive comparison would paint an unknown value as a green
 * "positive" delta, i.e. fabricated good news on a CFO-facing dashboard.
 *
 * Run: npx vitest run KpiCard.test
 */

import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import KpiCard from './KpiCard';

describe('KpiCard — null vs zero change', () => {
  it('renders `null` change as an honest "—" with no trend arrow and no positive/negative color', () => {
    render(<KpiCard title="GMV" value="₹1.2L" change={null} changeType="unknown" icon="gmv" />);

    // The unknown-state pill is present and shows the em dash.
    const pill = screen.getByLabelText('No comparison data available');
    expect(pill).toBeInTheDocument();
    expect(pill).toHaveTextContent('—');

    // Must NOT carry any percentage text (that would imply a measured value).
    expect(pill.textContent).not.toMatch(/%/);

    // Must NOT use the positive/negative/neutral solid color classes — those
    // are reserved for actually-measured deltas. This is the assertion that
    // fails if someone reverts to `change >= 0 ? 'positive' : 'negative'`,
    // because that would apply `bg-success-foreground` to a null value.
    expect(pill.className).not.toMatch(/bg-success-foreground/);
    expect(pill.className).not.toMatch(/bg-destructive-foreground/);

    // No TrendingUp/TrendingDown/Minus arrow — lucide icons render an <svg>
    // with a class name matching the icon; assert only one glyph (the help
    // icon) is present inside the pill, not a directional trend icon.
    expect(pill.querySelector('svg.lucide-trending-up')).not.toBeInTheDocument();
    expect(pill.querySelector('svg.lucide-trending-down')).not.toBeInTheDocument();
  });

  it('renders a genuine `0` change as a real flat "0%", distinct from the null/unknown state', () => {
    render(<KpiCard title="Revenue" value="₹1.2L" change={0} changeType="neutral" icon="revenue" />);

    // The flat pill shows "0%", not an em dash.
    expect(screen.getByText('0%')).toBeInTheDocument();
    expect(screen.queryByText('—')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('No comparison data available')).not.toBeInTheDocument();
  });

  it('does not render any change pill when `change` is undefined (no comparison tracked at all)', () => {
    render(<KpiCard title="Escrow Float" value="₹1.2L" icon="escrow" />);

    expect(screen.queryByText('0%')).not.toBeInTheDocument();
    expect(screen.queryByText('—')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('No comparison data available')).not.toBeInTheDocument();
  });
});
