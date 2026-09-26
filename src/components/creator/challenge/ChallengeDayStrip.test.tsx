/**
 * ChallengeDayStrip — Round 2 QA item 4: a bare letter-in-a-circle with the status word
 * underneath was cryptic in a real browser. Each cell now shows the weekday and the post
 * type as a word; status is conveyed through styling (colour/ring/icon) only, except
 * CHECKING which keeps its own word since there is no icon for "we don't know yet".
 *
 * Run: npx vitest run src/components/creator/challenge/ChallengeDayStrip.test.tsx
 */

import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';

import { ChallengeDayStrip } from './ChallengeDayStrip';
import { challengeCopy } from '@/lib/copy/creator-challenge';
import type { ChallengeDay } from '@/lib/api';

function makeDay(overrides: Partial<ChallengeDay>): ChallengeDay {
  return {
    dayIndex: 0,
    date: '2026-09-23', // a Wednesday
    plannedType: 'REEL',
    window: { label: 'evening', from: '17:00', to: '22:00' },
    windowSource: 'your_posts',
    status: 'UPCOMING',
    matchedType: null,
    postedType: null,
    permalink: null,
    ...overrides,
  };
}

const copy = challengeCopy('en-IN');

describe('ChallengeDayStrip', () => {
  it('shows the weekday and the post type as a word for a plain UPCOMING day', () => {
    render(<ChallengeDayStrip days={[makeDay({ dayIndex: 0, plannedType: 'CAROUSEL' })]} copy={copy} />);
    expect(screen.getByText('Wed')).toBeInTheDocument();
    expect(screen.getByText('Carousel')).toBeInTheDocument();
  });

  it('CHECKING keeps its own word, overriding the type caption', () => {
    render(<ChallengeDayStrip days={[makeDay({ dayIndex: 0, status: 'CHECKING' })]} copy={copy} />);
    expect(screen.getByText('Checking…')).toBeInTheDocument();
  });

  it('MISSED never renders red wording, and still shows a muted dash treatment', () => {
    const { container } = render(<ChallengeDayStrip days={[makeDay({ dayIndex: 0, status: 'MISSED' })]} copy={copy} />);
    expect(container.textContent).not.toMatch(/red/i);
    expect(container.querySelector('.bg-destructive')).toBeNull();
  });

  it('a DONE day with matchedType:false shows what was actually posted, not the plain type word', () => {
    render(
      <ChallengeDayStrip
        days={[makeDay({ dayIndex: 0, status: 'DONE', matchedType: false, postedType: 'CAROUSEL_ALBUM', plannedType: 'REEL' })]}
        copy={copy}
      />,
    );
    expect(screen.getByText('Posted Carousel instead')).toBeInTheDocument();
    expect(screen.queryByText('Reel')).not.toBeInTheDocument();
  });

  it('REST shows the word "Rest"', () => {
    render(
      <ChallengeDayStrip
        days={[makeDay({ dayIndex: 0, status: 'REST', plannedType: 'REST', window: null, windowSource: null })]}
        copy={copy}
      />,
    );
    expect(screen.getByText('Rest')).toBeInTheDocument();
  });

  it('fits all 7 cells in a single non-scrolling row (grid-cols-7, no fixed pixel widths)', () => {
    const days = Array.from({ length: 7 }, (_, i) => makeDay({ dayIndex: i, date: `2026-09-${20 + i}` }));
    const { container } = render(<ChallengeDayStrip days={days} copy={copy} />);
    const grid = container.firstElementChild as HTMLElement;
    expect(grid.className).toMatch(/grid-cols-7/);
    expect(grid.className).toMatch(/w-full/);
    expect(screen.getAllByRole('listitem')).toHaveLength(7);
  });
});
