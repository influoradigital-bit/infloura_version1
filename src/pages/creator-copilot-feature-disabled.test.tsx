/**
 * T-MEERA-CREATOR-PHASE-A gate review fix (Priya's frontend gate, item 3).
 *
 * MEERA_CREATOR_ENABLED rollback flag (Vikram): GET /creator/agent-preferences 404s with
 * { code: 'FEATURE_DISABLED' } in the standard envelope when it's off. The "Talk to Meera" entry
 * must be replaced by a calm, static explanation — no toast, no retry affordance — rather than
 * the generic connect-error text this page already shows for other failures.
 *
 * Run: npx vitest run src/pages/creator-copilot-feature-disabled.test.tsx
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import type { CreatorAgentPreferences } from '@/lib/api';

vi.mock('@/components/creator/creator-layout', () => ({
  CreatorLayout: ({ children }: { children: React.ReactNode }) => (
    <div data-testid="creator-layout">{children}</div>
  ),
}));

// The daily-suggestion section/hook talk to unrelated endpoints and aren't under test here.
vi.mock('@/components/creator/copilot/DailySuggestionSection', () => ({
  DailySuggestionSection: () => <div data-testid="daily-suggestion-section" />,
}));
vi.mock('@/components/creator/copilot/CopilotPreviewCard', () => ({
  CopilotPreviewCard: () => null,
}));
vi.mock('@/hooks/useDailySuggestion', () => ({
  useDailySuggestion: () => ({ status: 'idle' }),
}));

// vi.mock is hoisted above imports/top-level consts, so the mock fns referenced inside its
// factory must be created through vi.hoisted() (see vitest's hoisting docs) rather than plain
// top-level consts, which would still be in their TDZ when the factory runs.
const { getPreferences, toastMock } = vi.hoisted(() => ({
  getPreferences: vi.fn(),
  toastMock: vi.fn(),
}));

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    api: {
      ...actual.api,
      creatorAgentPrefs: {
        ...actual.api.creatorAgentPrefs,
        getPreferences: (...args: unknown[]) => getPreferences(...args),
      },
    },
  };
});

vi.mock('@/hooks/use-toast', () => ({
  toast: (...args: unknown[]) => toastMock(...args),
}));

import { ApiError } from '@/lib/api';
import CreatorCopilotPage from './creator-copilot';

const ENABLED_PREFS: CreatorAgentPreferences = {
  reel_floor: null,
  story_set_floor: null,
  post_floor: null,
  floor_currency: 'INR',
  excluded_categories: [],
  blocked_brands: [],
  approval_level: 0,
  creator_language: 'en-IN',
  brand_tone: 'FRIENDLY',
  working_hours_start: null,
  working_hours_end: null,
  working_hours_timezone: 'Asia/Kolkata',
  working_days: [],
  weekly_sponsored_limit: null,
  represented: false,
  agency_name: null,
  consent_accepted: false,
  consent_version: 'v1',
};

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/creator/copilot']}>
      <CreatorCopilotPage />
    </MemoryRouter>,
  );
}

describe('CreatorCopilotPage — MEERA_CREATOR_ENABLED off (gate review fix, item 3)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('replaces "Talk to Meera" with a calm, static message on FEATURE_DISABLED — no button, no toast, no retry', async () => {
    getPreferences.mockRejectedValue(new ApiError('FEATURE_DISABLED', 'Meera for creators is disabled', 404));

    renderPage();

    await waitFor(() => {
      expect(screen.getByText(/Meera for creators isn't available on your account yet/i)).toBeInTheDocument();
    });
    expect(screen.queryByRole('button', { name: /open meera/i })).not.toBeInTheDocument();
    // No retry affordance anywhere in the calm state (unlike the generic connect-error path,
    // which keeps the button live for the user to click again).
    expect(screen.queryByRole('button', { name: /try again|retry/i })).not.toBeInTheDocument();
    expect(toastMock).not.toHaveBeenCalled();
  });

  it('shows the normal "Talk to Meera" entry when the feature is enabled', async () => {
    getPreferences.mockResolvedValue(ENABLED_PREFS);

    renderPage();

    // Renders synchronously (not gated behind the mount probe) so the button never flashes away.
    expect(screen.getByRole('button', { name: /open meera/i })).toBeInTheDocument();
    await waitFor(() => {
      expect(screen.queryByText(/isn't available on your account yet/i)).not.toBeInTheDocument();
    });
  });

  it('a generic (non-FEATURE_DISABLED) load failure still shows the normal entry, not the calm state', async () => {
    getPreferences.mockRejectedValue(new ApiError('SERVER_UNAVAILABLE', 'temporarily down', 503));

    renderPage();

    expect(screen.getByRole('button', { name: /open meera/i })).toBeInTheDocument();
    await waitFor(() => {
      expect(screen.queryByText(/isn't available on your account yet/i)).not.toBeInTheDocument();
    });
  });
});
