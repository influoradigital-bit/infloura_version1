/**
 * ChallengeCard — creator 7-day challenge (CHALLENGE-SPEC.md, 2026-09-23, Frontend §9).
 *
 * Mocks `useCreatorChallenge` directly (same convention as
 * `DailySuggestionSection.test.tsx` mocking `useDailySuggestion`) since this component's
 * contract is entirely in terms of the hook's return shape.
 *
 * Covers every honesty rule from the spec:
 *  - each top-level state renders (not connected / intro / active / completed)
 *  - % and point changes are hidden (the plain `note` shown instead) when `!enoughToCompare`
 *  - "suggested" wording appears only when `windowSource === 'suggestion'`, and "your best
 *    time" never appears anywhere, in either wording state
 *  - "Write the script" / "Give me an idea" only ever call `onAskMeera` (prefill) — there is
 *    no send-capable prop on this component at all, so a passing test here is structural proof
 *    nothing here can send on the creator's behalf
 *  - a 409 CHALLENGE_ALREADY_ACTIVE start error renders a plain message, not a raw code/toast
 *
 * Run: npx vitest run src/components/creator/challenge/ChallengeCard.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { ChallengeCard } from './ChallengeCard';
import { useCreatorChallenge } from '@/hooks/useCreatorChallenge';
import type { UseCreatorChallengeResult } from '@/hooks/useCreatorChallenge';
import { ApiError } from '@/lib/api';
import type { ActiveChallenge, ChallengeDay, ChallengeState } from '@/lib/api';

vi.mock('@/hooks/use-toast', () => ({
  useToast: () => ({ toast: vi.fn() }),
}));

vi.mock('@/hooks/useCreatorChallenge', () => ({
  useCreatorChallenge: vi.fn(),
}));

const mockedHook = vi.mocked(useCreatorChallenge);

const BASE: UseCreatorChallengeResult = {
  data: null,
  status: 'loading',
  error: null,
  starting: false,
  ending: false,
  startError: null,
  endError: null,
  start: vi.fn(),
  end: vi.fn(),
  retry: vi.fn(),
};

function makeDay(overrides: Partial<ChallengeDay>): ChallengeDay {
  return {
    dayIndex: 0,
    date: '2026-09-23',
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

function makeActive(overrides: Partial<ActiveChallenge> = {}): ActiveChallenge {
  return {
    id: '01J_TEST',
    startedOn: '2026-09-21',
    dayNumber: 3,
    streak: 2,
    days: [
      makeDay({ dayIndex: 0, status: 'DONE', matchedType: true, postedType: 'VIDEO' }),
      makeDay({ dayIndex: 1, status: 'DONE', matchedType: false, postedType: 'IMAGE' }),
      makeDay({
        dayIndex: 2,
        status: 'TODAY',
        plannedType: 'POST',
        window: { label: 'evening', from: '17:00', to: '22:00' },
        windowSource: 'suggestion',
      }),
      makeDay({ dayIndex: 3, status: 'UPCOMING' }),
      makeDay({ dayIndex: 4, status: 'REST', plannedType: 'REST', window: null, windowSource: null }),
      makeDay({ dayIndex: 5, status: 'UPCOMING' }),
      makeDay({ dayIndex: 6, status: 'UPCOMING' }),
    ],
    ...overrides,
  };
}

const BASE_STATE: ChallengeState = {
  instagramConnected: true,
  active: null,
  lastCompleted: null,
  comparison: {
    thisWeek: { from: '2026-09-16', to: '2026-09-22', posts: 3, settledPosts: 2, reach: 8400, engagementRate: '4.1%' },
    lastWeek: { from: '2026-09-09', to: '2026-09-15', posts: 1, settledPosts: 1, reach: 6360, engagementRate: '4.0%' },
    reachChangePercent: null,
    engagementChangePoints: null,
    enoughToCompare: false,
    note: 'Not enough settled posts in both weeks to compare yet.',
  },
};

describe('ChallengeCard', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('not connected: short explanation + the existing connect entry point', () => {
    mockedHook.mockReturnValue({
      ...BASE,
      status: 'ready',
      data: { ...BASE_STATE, instagramConnected: false },
    });
    render(<ChallengeCard onAskMeera={vi.fn()} />);

    expect(screen.getByText('Connect Instagram to start the challenge')).toBeInTheDocument();
    // The EXISTING connect entry point (IGConnectPrompt), not a forked one.
    expect(screen.getByRole('button', { name: /connect instagram/i })).toBeInTheDocument();
  });

  it('no active challenge: what it is in two lines + Start CTA', async () => {
    const start = vi.fn();
    mockedHook.mockReturnValue({ ...BASE, status: 'ready', data: BASE_STATE, start });
    render(<ChallengeCard onAskMeera={vi.fn()} />);

    expect(screen.getByText('A 7-day plan, built from your own posts')).toBeInTheDocument();
    const cta = screen.getByRole('button', { name: 'Start my 7-day challenge' });
    await userEvent.click(cta);
    expect(start).toHaveBeenCalledTimes(1);
  });

  it('COMPLETED (via lastCompleted): "posted on N of M days" + start-next CTA', () => {
    mockedHook.mockReturnValue({
      ...BASE,
      status: 'ready',
      data: {
        ...BASE_STATE,
        active: null,
        lastCompleted: { id: '01J_OLD', startedOn: '2026-09-09', daysDone: 5, daysPlanned: 6 },
      },
    });
    render(<ChallengeCard onAskMeera={vi.fn()} />);

    expect(screen.getByText('You posted on 5 of 6 days')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Start the next 7 days' })).toBeInTheDocument();
  });

  it('active: comparison row, today\'s task, the strip, and streak all render', () => {
    mockedHook.mockReturnValue({
      ...BASE,
      status: 'ready',
      data: { ...BASE_STATE, active: makeActive() },
    });
    render(<ChallengeCard onAskMeera={vi.fn()} />);

    expect(screen.getByText('Your 7-day challenge · Day 3 of 7')).toBeInTheDocument();
    expect(screen.getByText('2 day streak')).toBeInTheDocument();
    expect(screen.getByText("Today's task")).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Write the script' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Give me an idea' })).toBeInTheDocument();
  });

  it('"suggested" wording appears when windowSource is suggestion, and "your best time" is never said', () => {
    mockedHook.mockReturnValue({
      ...BASE,
      status: 'ready',
      data: { ...BASE_STATE, active: makeActive() },
    });
    const { container } = render(<ChallengeCard onAskMeera={vi.fn()} />);

    expect(screen.getByText('Suggested')).toBeInTheDocument();
    expect(container.textContent).not.toMatch(/your best time/i);
    expect(container.textContent).not.toMatch(/growing/i);
  });

  it('a DONE day with matchedType: false shows what was actually posted, not a forced match', () => {
    mockedHook.mockReturnValue({
      ...BASE,
      status: 'ready',
      data: { ...BASE_STATE, active: makeActive() },
    });
    render(<ChallengeCard onAskMeera={vi.fn()} />);

    // dayIndex 1 was DONE with matchedType:false, postedType 'IMAGE' -> mapped to "Post".
    expect(screen.getByText('Posted Post instead')).toBeInTheDocument();
  });

  it('% and point changes are HIDDEN when enoughToCompare is false — the plain note shows instead', () => {
    mockedHook.mockReturnValue({
      ...BASE,
      status: 'ready',
      data: { ...BASE_STATE, active: makeActive() },
    });
    const { container } = render(<ChallengeCard onAskMeera={vi.fn()} />);

    expect(screen.getByText('Not enough settled posts in both weeks to compare yet.')).toBeInTheDocument();
    expect(container.textContent).not.toMatch(/%\s*reach/);
  });

  it('hides a change figure the API sent while enoughToCompare is false (the flag wins)', () => {
    // Every other fixture paired enoughToCompare:false with null figures, so removing the guard
    // was invisible (Meera's falsification, 2026-09-24). The flag is the rule, not the nulls.
    mockedHook.mockReturnValue({
      ...BASE,
      status: 'ready',
      data: {
        ...BASE_STATE,
        active: makeActive(),
        comparison: {
          ...BASE_STATE.comparison,
          enoughToCompare: false,
          reachChangePercent: 12,
          engagementChangePoints: 0.4,
        },
      },
    });
    const { container } = render(<ChallengeCard onAskMeera={vi.fn()} />);

    expect(container.textContent).not.toContain('+12%');
    expect(container.textContent).not.toMatch(/12\s*%/);
    expect(screen.getByText('Not enough settled posts in both weeks to compare yet.')).toBeInTheDocument();
  });

  it('shows the reach change once enoughToCompare is true', () => {
    mockedHook.mockReturnValue({
      ...BASE,
      status: 'ready',
      data: {
        ...BASE_STATE,
        active: makeActive(),
        comparison: {
          ...BASE_STATE.comparison,
          enoughToCompare: true,
          reachChangePercent: 12,
          note: null,
        },
      },
    });
    render(<ChallengeCard onAskMeera={vi.fn()} />);

    expect(screen.getByText('+12% reach')).toBeInTheDocument();
  });

  it('"Write the script" / "Give me an idea" only ever PREFILL via onAskMeera — never send', async () => {
    const onAskMeera = vi.fn();
    mockedHook.mockReturnValue({
      ...BASE,
      status: 'ready',
      data: { ...BASE_STATE, active: makeActive() },
    });
    render(<ChallengeCard onAskMeera={onAskMeera} />);

    await userEvent.click(screen.getByRole('button', { name: 'Write the script' }));
    expect(onAskMeera).toHaveBeenCalledTimes(1);
    expect(typeof onAskMeera.mock.calls[0][0]).toBe('string');

    await userEvent.click(screen.getByRole('button', { name: 'Give me an idea' }));
    expect(onAskMeera).toHaveBeenCalledTimes(2);
    // Asks Meera for an idea for TODAY'S planned post. It must not read as the trend-based
    // "content idea" feature, which can be switched off (trends-off-copy gate, T-TSOFF-0920).
    expect(onAskMeera.mock.calls[1][0]).toMatch(/^Give me an idea for today's (reel|carousel|post)\.$/);
  });

  it('a 409 CHALLENGE_ALREADY_ACTIVE start error renders a plain message', () => {
    mockedHook.mockReturnValue({
      ...BASE,
      status: 'ready',
      data: BASE_STATE,
      startError: new ApiError('CHALLENGE_ALREADY_ACTIVE', 'A challenge is already active', 409),
    });
    render(<ChallengeCard onAskMeera={vi.fn()} />);

    expect(screen.getByText('A challenge is already active.')).toBeInTheDocument();
  });

  it('a GET error renders the retry affordance, not a blank page', async () => {
    const retry = vi.fn();
    mockedHook.mockReturnValue({ ...BASE, status: 'error', error: "Couldn't load your challenge.", retry });
    render(<ChallengeCard onAskMeera={vi.fn()} />);

    expect(screen.getByText("Couldn't load your challenge.")).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /retry/i }));
    expect(retry).toHaveBeenCalledTimes(1);
  });

  it('"Write the script" is the solid primary action; "Give me an idea" stays outline', () => {
    mockedHook.mockReturnValue({
      ...BASE,
      status: 'ready',
      data: { ...BASE_STATE, active: makeActive() },
    });
    render(<ChallengeCard onAskMeera={vi.fn()} />);

    const script = screen.getByRole('button', { name: 'Write the script' });
    const idea = screen.getByRole('button', { name: 'Give me an idea' });
    // Default Button variant (solid primary) has no `border` class; `outline` does.
    expect(script.className).toMatch(/bg-primary/);
    expect(idea.className).not.toMatch(/bg-primary/);
  });

  describe('End challenge (Round 2 QA item 1)', () => {
    it('shows a quiet "End challenge" text control at the bottom of the active card', () => {
      mockedHook.mockReturnValue({
        ...BASE,
        status: 'ready',
        data: { ...BASE_STATE, active: makeActive() },
      });
      render(<ChallengeCard onAskMeera={vi.fn()} />);

      const endButton = screen.getByRole('button', { name: 'End challenge' });
      expect(endButton).toBeInTheDocument();
      // "quiet", not a big red button.
      expect(endButton.className).not.toMatch(/bg-destructive/);
      expect(endButton.className).not.toMatch(/bg-primary/);
    });

    it('opens a confirm dialog; cancelling does nothing', async () => {
      const end = vi.fn();
      mockedHook.mockReturnValue({
        ...BASE,
        status: 'ready',
        data: { ...BASE_STATE, active: makeActive() },
        end,
      });
      render(<ChallengeCard onAskMeera={vi.fn()} />);

      await userEvent.click(screen.getByRole('button', { name: 'End challenge' }));
      expect(screen.getByText('End this challenge?')).toBeInTheDocument();
      expect(screen.getByText("The days you've done stay counted.")).toBeInTheDocument();

      await userEvent.click(screen.getByRole('button', { name: 'Keep going' }));
      expect(end).not.toHaveBeenCalled();
    });

    it('confirming calls end() once, and the card then shows the not-started state', async () => {
      const end = vi.fn().mockResolvedValue(undefined);
      // First render: active challenge. After confirming, simulate the hook's own
      // start->refetch-style update (useCreatorChallenge.test.ts covers the real refetch)
      // by re-mocking the hook to what it returns once `end` has resolved and rerendering —
      // this is the "card then shows the not-started / last-completed state" assertion.
      mockedHook.mockReturnValue({
        ...BASE,
        status: 'ready',
        data: { ...BASE_STATE, active: makeActive() },
        end,
      });
      const { rerender } = render(<ChallengeCard onAskMeera={vi.fn()} />);

      await userEvent.click(screen.getByRole('button', { name: 'End challenge' }));
      await userEvent.click(screen.getByRole('button', { name: 'End it' }));

      expect(end).toHaveBeenCalledTimes(1);
      expect(end).toHaveBeenCalledWith('01J_TEST');

      mockedHook.mockReturnValue({
        ...BASE,
        status: 'ready',
        data: { ...BASE_STATE, active: null, lastCompleted: null },
        end,
      });
      rerender(<ChallengeCard onAskMeera={vi.fn()} />);

      expect(screen.getByText('A 7-day plan, built from your own posts')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Start my 7-day challenge' })).toBeInTheDocument();
    });
  });
});
