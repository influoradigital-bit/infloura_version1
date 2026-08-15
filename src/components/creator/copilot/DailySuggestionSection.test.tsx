/**
 * DailySuggestionSection — no_suggestion_today renders the empty state, not a blank page
 * (CR-64 / F-0107).
 *
 * WHY THIS FILE EXISTS
 * --------------------
 * `useDailySuggestion` collapses the backend's `no_suggestion_today` wire status into the same
 * `'dismissed'` UI status as a creator-dismissed suggestion — but `no_suggestion_today` has
 * `suggestion: null` (there is nothing to dismiss). `DailySuggestionCard` renders both `ready`
 * and `dismissed` bodies, but bails to `return null` whenever `!suggestion` — so a normal,
 * expected "nothing today" server response rendered a completely blank Co-pilot page.
 * `SuggestionEmptyState` already had the exact copy for this (`reason="no_suggestion_today"`)
 * but no call site ever passed it. The fix special-cases `dismissed + no suggestion` in the
 * section's single switch(status) site, before falling through to `DailySuggestionCard`.
 *
 * HARNESS NOTES — mocks `useDailySuggestion` directly rather than react-query, since this
 * component's contract is entirely in terms of the hook's return shape (its own docs: "Ananya
 * owns the components, which consume this hook's return shape 1:1").
 *
 * Run: npx vitest run src/components/creator/copilot/DailySuggestionSection.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { DailySuggestionSection } from './DailySuggestionSection';
import { useDailySuggestion } from '@/hooks/useDailySuggestion';
import type { UseDailySuggestionResult } from '@/hooks/useDailySuggestion';

vi.mock('@/hooks/use-toast', () => ({
  useToast: () => ({ toast: vi.fn() }),
}));

vi.mock('@/hooks/useDailySuggestion', () => ({
  useDailySuggestion: vi.fn(),
}));

const mockedHook = vi.mocked(useDailySuggestion);

const BASE: UseDailySuggestionResult = {
  suggestion: null,
  status: 'idle',
  requiresBusinessAccount: false,
  error: null,
  dismiss: vi.fn(),
  markActed: vi.fn(),
  retry: vi.fn(),
};

const SUGGESTION = {
  id: 'sug_1',
  theme: 'Skincare',
  headline: 'Try a GRWM reel',
  contentIdea: 'Get ready with me using the new serum',
  expiresAt: '2026-08-11T00:00:00.000Z',
};

describe('DailySuggestionSection — no_suggestion_today (CR-64 / F-0107)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders the purpose-built empty state, not a blank page, when dismissed carries no suggestion', () => {
    mockedHook.mockReturnValue({ ...BASE, status: 'dismissed', suggestion: null });
    const { container } = render(<DailySuggestionSection />);

    expect(screen.getByText('No new idea today — check back tomorrow.')).toBeInTheDocument();
    // The regression this guards against: the section renders NOTHING at all.
    expect(container).not.toBeEmptyDOMElement();
  });

  it('still renders the "Next one tomorrow" card when dismissed carries a real suggestion', () => {
    // A creator-dismissed suggestion — distinct from no_suggestion_today, and must not be
    // swept into the same empty-state branch (it has different, dismiss-history-aware copy
    // that a future reader might expect DailySuggestionCard to own).
    mockedHook.mockReturnValue({ ...BASE, status: 'dismissed', suggestion: SUGGESTION });
    render(<DailySuggestionSection />);

    expect(screen.getByText('Next one tomorrow.')).toBeInTheDocument();
    expect(screen.queryByText('No new idea today — check back tomorrow.')).not.toBeInTheDocument();
  });

  it('renders the ready card unaffected', () => {
    mockedHook.mockReturnValue({ ...BASE, status: 'ready', suggestion: SUGGESTION });
    render(<DailySuggestionSection />);

    expect(screen.getByText('Try a GRWM reel')).toBeInTheDocument();
  });

  it('renders the pending_tagging empty state unaffected', () => {
    mockedHook.mockReturnValue({ ...BASE, status: 'loading' });
    render(<DailySuggestionSection />);

    expect(screen.getByText('Usually ready within a day.')).toBeInTheDocument();
  });

  // Priya review: the guard's placement relative to the earlier status checks is exactly what
  // makes it correct — a plausible "simplification" that hoists it above them (e.g.
  // `!suggestion && status !== 'ready'`) would silently break these two, since both idle
  // sub-branches and error also have suggestion === null. Neither has a text collision with
  // the empty-state copy, so a broken guard fails these loudly rather than passing by luck.
  it('renders the Connect Instagram prompt unaffected when not yet connected', () => {
    mockedHook.mockReturnValue({ ...BASE, status: 'idle', requiresBusinessAccount: false });
    render(<DailySuggestionSection />);

    expect(screen.getByRole('button', { name: 'Connect Instagram' })).toBeInTheDocument();
    expect(screen.queryByText('No new idea today — check back tomorrow.')).not.toBeInTheDocument();
  });

  it('renders the business-account-required prompt unaffected for a personal IG account', () => {
    mockedHook.mockReturnValue({ ...BASE, status: 'idle', requiresBusinessAccount: true });
    render(<DailySuggestionSection />);

    expect(screen.getByText('Your Instagram needs a quick switch')).toBeInTheDocument();
    expect(screen.queryByText('No new idea today — check back tomorrow.')).not.toBeInTheDocument();
  });

  it('renders the error state with a working retry, unaffected', () => {
    mockedHook.mockReturnValue({ ...BASE, status: 'error', error: 'Server unavailable' });
    render(<DailySuggestionSection />);

    expect(screen.getByText('Couldn’t load today’s idea.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument();
    expect(screen.queryByText('No new idea today — check back tomorrow.')).not.toBeInTheDocument();
  });
});
