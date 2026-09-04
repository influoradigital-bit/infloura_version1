/**
 * T-MEERA-CREATOR-PHASE-A gate-fix round 2 (Priya Q1) — DealTermsSummary rendering.
 *
 * See deal-terms-summary.tsx and creator-deal-mappers.test.ts's "dealTerms threading" describe
 * for the full finding. This pins the presentational half: given a real DealTermsDto shape,
 * every field renders as readable text, and the "no terms set at all" case is handled by
 * callers (this component always assumes `terms` is defined — callers gate on presence).
 */
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { DealTerms } from '@/lib/types';
import { DealTermsSummary } from './deal-terms-summary';

describe('DealTermsSummary', () => {
  it('renders a fixed usage window, channels, named-brand exclusivity, and revisions', () => {
    const terms: DealTerms = {
      usageMonths: 6,
      usagePerpetual: false,
      usageChannels: ['ORGANIC', 'PAID_ADS'],
      exclusivityDays: 30,
      exclusivityScope: 'NAMED_BRANDS',
      exclusivityBrands: ['Nykaa', 'Mamaearth'],
      maxRevisions: 3,
    };
    render(<DealTermsSummary terms={terms} />);

    expect(screen.getByText('6 months')).toBeInTheDocument();
    expect(screen.getByText('Organic social, Paid ads')).toBeInTheDocument();
    expect(screen.getByText('Excluded: Nykaa, Mamaearth (30 days)')).toBeInTheDocument();
    expect(screen.getByText('3')).toBeInTheDocument();
  });

  it('renders perpetual usage and no exclusivity honestly', () => {
    const terms: DealTerms = {
      usageMonths: null,
      usagePerpetual: true,
      usageChannels: [],
      exclusivityDays: null,
      exclusivityScope: 'NONE',
      exclusivityBrands: [],
      maxRevisions: 2,
    };
    render(<DealTermsSummary terms={terms} />);

    expect(screen.getByText('Perpetual')).toBeInTheDocument();
    // Usage channels row: empty list -> honest "Not specified", not a blank/zero.
    expect(screen.getAllByText('Not specified').length).toBeGreaterThan(0);
    expect(screen.getByText('None')).toBeInTheDocument();
  });

  it('renders CATEGORY exclusivity without a brand list', () => {
    const terms: DealTerms = {
      usageMonths: 12,
      usagePerpetual: false,
      usageChannels: ['WEBSITE'],
      exclusivityDays: 90,
      exclusivityScope: 'CATEGORY',
      exclusivityBrands: [],
      maxRevisions: 1,
    };
    render(<DealTermsSummary terms={terms} />);

    expect(screen.getByText('Category exclusivity (90 days)')).toBeInTheDocument();
  });
});
