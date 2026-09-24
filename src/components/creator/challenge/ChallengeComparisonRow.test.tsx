/**
 * ChallengeComparisonRow — Round 2 QA item 2: a week whose `reach`/`engagementRate` are drawn
 * from fewer settled posts than it actually had gets its own explanatory line, so a low number
 * doesn't read as a bad week when it's really just posts still settling.
 *
 * Run: npx vitest run src/components/creator/challenge/ChallengeComparisonRow.test.tsx
 */

import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';

import { ChallengeComparisonRow } from './ChallengeComparisonRow';
import { challengeCopy } from '@/lib/copy/creator-challenge';
import type { ChallengeComparison } from '@/lib/api';

const BASE: ChallengeComparison = {
  thisWeek: { from: '2026-09-16', to: '2026-09-22', posts: 3, settledPosts: 3, reach: 8400, engagementRate: '4.1%' },
  lastWeek: { from: '2026-09-09', to: '2026-09-15', posts: 2, settledPosts: 2, reach: 6360, engagementRate: '4.0%' },
  reachChangePercent: null,
  engagementChangePoints: null,
  enoughToCompare: false,
  note: 'Not enough settled posts in both weeks to compare yet.',
};

const copy = challengeCopy('en-IN');

describe('ChallengeComparisonRow — settling note (Round 2 QA item 2)', () => {
  it('shows no settling note when settledPosts === posts for both weeks', () => {
    render(<ChallengeComparisonRow comparison={BASE} copy={copy} />);
    expect(screen.queryByText(/still collecting views/)).not.toBeInTheDocument();
  });

  it('shows the settling note under thisWeek only when thisWeek.settledPosts < thisWeek.posts', () => {
    const comparison: ChallengeComparison = {
      ...BASE,
      thisWeek: { ...BASE.thisWeek, posts: 3, settledPosts: 1 },
    };
    render(<ChallengeComparisonRow comparison={comparison} copy={copy} />);
    expect(
      screen.getByText('Reach and engagement from 1 settled post — newer posts are still collecting views.'),
    ).toBeInTheDocument();
  });

  it('shows the settling note under lastWeek only when lastWeek.settledPosts < lastWeek.posts', () => {
    const comparison: ChallengeComparison = {
      ...BASE,
      lastWeek: { ...BASE.lastWeek, posts: 4, settledPosts: 2 },
    };
    render(<ChallengeComparisonRow comparison={comparison} copy={copy} />);
    expect(
      screen.getByText('Reach and engagement from 2 settled posts — newer posts are still collecting views.'),
    ).toBeInTheDocument();
  });

  it('shows both settling notes when both weeks are under-settled', () => {
    const comparison: ChallengeComparison = {
      ...BASE,
      thisWeek: { ...BASE.thisWeek, posts: 3, settledPosts: 1 },
      lastWeek: { ...BASE.lastWeek, posts: 2, settledPosts: 0 },
    };
    render(<ChallengeComparisonRow comparison={comparison} copy={copy} />);
    expect(screen.getAllByText(/still collecting views/)).toHaveLength(2);
  });
});

describe('ChallengeComparisonRow — engagement basis label', () => {
  // This card's engagement figure divides by REACH, per post
  // (CreatorChallengeService.averageRawEngagementRate: engagement / reach) — a different
  // denominator from the metrics card's "Engagement rate (per follower)" (CreatorMetric
  // .avgEngagementRate: (likes + comments) / followers). Naming the basis here stops the two
  // numbers from reading as the same metric.
  it('labels both weeks\' engagement figures "per reach", not bare "engagement"', () => {
    render(<ChallengeComparisonRow comparison={BASE} copy={copy} />);
    expect(screen.getByText(/4\.1% engagement per reach/)).toBeInTheDocument();
    expect(screen.getByText(/4\.0% engagement per reach/)).toBeInTheDocument();
  });

  it('is bilingual, matching the rest of this card\'s Hindi copy', () => {
    const hiCopy = challengeCopy('hi-IN');
    render(<ChallengeComparisonRow comparison={BASE} copy={hiCopy} />);
    expect(screen.getByText(/4\.1% engagement \(reach के हिसाब से\)/)).toBeInTheDocument();
  });
});
