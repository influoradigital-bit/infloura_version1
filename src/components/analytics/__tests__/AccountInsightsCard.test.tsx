/**
 * The creator's account numbers for the last 28 days (2026-09-24): each number as Instagram
 * reported it, "Not reported" (never 0) for one it did not, and an honest empty state before the
 * first fetch.
 *
 * Run: npx vitest run src/components/analytics/__tests__/AccountInsightsCard.test.tsx
 */
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import type { CreatorAccountInsights } from '@/lib/api';
import { AccountInsightsCard } from '../AccountInsightsCard';

const DATA: CreatorAccountInsights = {
  hasData: true,
  periodStart: '2026-08-27',
  periodEnd: '2026-09-23',
  reach: 12400,
  views: 148210,
  totalInteractions: 1930,
  accountsEngaged: 822,
  profileLinksTaps: null,
  fetchedAt: '2026-09-24T00:00:00Z',
};

function tile(label: string): string {
  return screen.getByText(label).closest('[role="listitem"]')?.textContent ?? '';
}

describe('AccountInsightsCard', () => {
  it('shows each number Instagram reported, grouped the Indian way', () => {
    render(<AccountInsightsCard data={DATA} />);
    expect(tile('Accounts reached')).toContain('12,400');
    expect(tile('Views')).toContain('1,48,210');
    expect(tile('Interactions')).toContain('1,930');
    expect(tile('Accounts engaged')).toContain('822');
  });

  it('a number Instagram did not report says so, never 0', () => {
    render(<AccountInsightsCard data={DATA} />);
    expect(tile('Profile link taps')).toContain('Not reported');
    expect(tile('Profile link taps')).not.toMatch(/\b0\b/);
  });

  it('names the 28-day window as calendar dates', () => {
    render(<AccountInsightsCard data={DATA} />);
    expect(screen.getByText('27 Aug 2026 to 23 Sept 2026, from Instagram')).toBeInTheDocument();
  });

  it('before the first fetch: an empty state, no numbers', () => {
    render(<AccountInsightsCard data={{ ...DATA, hasData: false }} />);
    expect(screen.getByText('No account numbers yet')).toBeInTheDocument();
    expect(screen.queryByTestId('account-insights-card')).toBeNull();
  });
});
