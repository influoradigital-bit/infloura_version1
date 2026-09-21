/**
 * T-TSOFF-0920 — the creator Co-pilot surfaces render an honest off-state, and nothing else.
 *
 * WHY THIS FILE EXISTS
 * --------------------
 * Three separate things used to render on /creator/copilot while the trend feature was switched
 * off, each of them a promise the backend could not keep:
 *
 *  1. `SuggestionEmptyState reason="no_suggestion_today"` — "No new idea today — check back
 *     tomorrow." There is no tomorrow while ingest is off.
 *  2. `SuggestionEmptyState reason="pending_tagging"` — "Reading your recent posts — your first
 *     idea lands by tomorrow morning." Nothing is reading anything; `CreatorThemeTaggingJob` is
 *     off too (EV-068).
 *  3. `IGConnectPrompt` — "Get your first daily idea / Connect Instagram". A dead control:
 *     completing Meta OAuth cannot produce a suggestion from an empty `trends` table.
 *
 * Each assertion below is paired with its feature-ON counterpart, so the gate cannot be satisfied
 * by simply never rendering the feature.
 *
 * Run: npx vitest run src/components/creator/copilot/daily-suggestion-section.trends-off.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { DailySuggestionSection } from './DailySuggestionSection';
import { useDailySuggestion } from '@/hooks/useDailySuggestion';
import type { UseDailySuggestionResult } from '@/hooks/useDailySuggestion';

vi.mock('@/hooks/use-toast', () => ({ useToast: () => ({ toast: vi.fn() }) }));
vi.mock('@/hooks/useDailySuggestion', () => ({ useDailySuggestion: vi.fn() }));

const mockedHook = vi.mocked(useDailySuggestion);

const BASE: UseDailySuggestionResult = {
  suggestion: null,
  status: 'idle',
  requiresBusinessAccount: false,
  verifyingConnection: false,
  error: null,
  dismiss: vi.fn(),
  markActed: vi.fn(),
  retry: vi.fn(),
};

describe('DailySuggestionSection — T-TSOFF-0920 disabled state', () => {
  beforeEach(() => vi.clearAllMocks());

  it('renders the plain "not available yet" card', () => {
    mockedHook.mockReturnValue({ ...BASE, status: 'disabled' });
    render(<DailySuggestionSection />);

    expect(screen.getByText(/aren’t available yet/i)).toBeInTheDocument();
    expect(screen.getByText(/switched off on Influora right now/i)).toBeInTheDocument();
  });

  it('promises nothing: no "tomorrow", no "check back", no "first idea"', () => {
    mockedHook.mockReturnValue({ ...BASE, status: 'disabled' });
    const { container } = render(<DailySuggestionSection />);

    const text = container.textContent ?? '';
    expect(text).not.toMatch(/tomorrow/i);
    expect(text).not.toMatch(/check back/i);
    expect(text).not.toMatch(/first idea/i);
    expect(text).not.toMatch(/reading your recent posts/i);
    expect(text).not.toMatch(/soon|coming/i);
  });

  it('has no controls at all — nothing to click that cannot work', () => {
    mockedHook.mockReturnValue({ ...BASE, status: 'disabled' });
    const { container } = render(<DailySuggestionSection />);

    expect(screen.queryByRole('button')).toBeNull();
    expect(screen.queryByRole('link')).toBeNull();
    expect(container.querySelectorAll('button, a, input, [role="button"]')).toHaveLength(0);
  });

  it('never renders the Connect Instagram dead control, even though the creator is unconnected', () => {
    // `requiresBusinessAccount` and an unconnected creator are BOTH true here: this is exactly
    // the state that used to route to IGConnectPrompt / BusinessAccountRequired.
    mockedHook.mockReturnValue({ ...BASE, status: 'disabled', requiresBusinessAccount: true });
    render(<DailySuggestionSection />);

    expect(screen.queryByText(/connect instagram/i)).toBeNull();
    expect(screen.queryByText(/get your first daily idea/i)).toBeNull();
  });

  it('renders no fabricated suggestion content — not even a sample headline', () => {
    // A stale suggestion object in the hook's cache must not leak through the disabled branch.
    mockedHook.mockReturnValue({
      ...BASE,
      status: 'disabled',
      suggestion: {
        id: 'sug_stale',
        theme: 'Skincare',
        headline: 'Try a GRWM reel',
        contentIdea: 'Get ready with me using the new serum',
        expiresAt: '2099-01-01T00:00:00.000Z',
      },
    });
    const { container } = render(<DailySuggestionSection />);

    expect(container.textContent).not.toMatch(/GRWM|serum|Skincare/i);
  });

  // ---- feature ON: the same component still does its job -------------------------------------

  it('ON + ready: the real suggestion still renders (gate is not a mute button)', () => {
    mockedHook.mockReturnValue({
      ...BASE,
      status: 'ready',
      suggestion: {
        id: 'sug_1',
        theme: 'Skincare',
        headline: 'Try a GRWM reel',
        contentIdea: 'Get ready with me using the new serum',
        expiresAt: '2099-01-01T00:00:00.000Z',
      },
    });
    render(<DailySuggestionSection />);

    expect(screen.getByText(/Try a GRWM reel/i)).toBeInTheDocument();
  });

  it('ON + idle: the Connect Instagram CTA is restored', () => {
    mockedHook.mockReturnValue({ ...BASE, status: 'idle' });
    render(<DailySuggestionSection />);

    expect(screen.getByText(/connect instagram/i)).toBeInTheDocument();
  });

  it('ON + no suggestion today: the "check back tomorrow" copy is still allowed', () => {
    // Proves the disabled branch did not simply delete the honest empty state; when the feature
    // really is running, "tomorrow" is a true statement and must keep working.
    mockedHook.mockReturnValue({ ...BASE, status: 'dismissed', suggestion: null });
    render(<DailySuggestionSection />);

    expect(screen.getByText(/check back tomorrow/i)).toBeInTheDocument();
  });
});
