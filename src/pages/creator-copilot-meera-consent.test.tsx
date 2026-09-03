/**
 * T-MEERA-CREATOR-PHASE-A (A6/A10) — creator-copilot's "Talk to Meera" consent gate.
 * Mock-API mode: `api.creatorAgentPrefs.getPreferences()` resolves the module's own
 * `MOCK_CREATOR_AGENT_PREFS` (consent_accepted: false), so opening Meera must show the
 * consent screen before the chat panel, and accepting must open the chat.
 *
 * Run: npx vitest run src/pages/creator-copilot-meera-consent.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import CreatorCopilotPage from './creator-copilot';

vi.mock('@/components/creator/creator-layout', () => ({
  CreatorLayout: ({ children }: { children: React.ReactNode }) => (
    <div data-testid="creator-layout">{children}</div>
  ),
}));

// The daily-suggestion section/hook talk to unrelated endpoints and aren't under test here —
// stub the section itself so this test only exercises the Meera entry point.
vi.mock('@/components/creator/copilot/DailySuggestionSection', () => ({
  DailySuggestionSection: () => <div data-testid="daily-suggestion-section" />,
}));
vi.mock('@/components/creator/copilot/CopilotPreviewCard', () => ({
  CopilotPreviewCard: () => null,
}));
vi.mock('@/hooks/useDailySuggestion', () => ({
  useDailySuggestion: () => ({ status: 'idle' }),
}));

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/creator/copilot']}>
      <CreatorCopilotPage />
    </MemoryRouter>,
  );
}

describe('CreatorCopilotPage — Meera consent gate (A6/A10)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('shows the consent screen before the chat panel on first open', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByRole('button', { name: /open meera/i }));

    // MOCK_CREATOR_AGENT_PREFS.creator_language is 'hi-IN' — the consent dialog renders
    // its Hindi copy, not the chat panel.
    await waitFor(() => {
      expect(screen.getByText('Meera से बात करें')).toBeInTheDocument();
    });
    expect(screen.queryByPlaceholderText(/ask meera/i)).not.toBeInTheDocument();
  });

  it('opens the chat panel after accepting consent', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByRole('button', { name: /open meera/i }));
    await waitFor(() => screen.getByText('Meera से बात करें'));

    await user.click(screen.getByRole('button', { name: 'स्वीकार करें' }));

    await waitFor(() => {
      expect(screen.queryByText('Meera से बात करें')).not.toBeInTheDocument();
    });
    expect(screen.getByPlaceholderText(/ask meera/i)).toBeInTheDocument();
  });
});
