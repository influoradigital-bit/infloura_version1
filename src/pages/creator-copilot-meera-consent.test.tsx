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

    // MOCK_CREATOR_AGENT_PREFS.creator_language is 'en-IN' (English is the default since
    // 2026-09-23) — the consent dialog renders its English copy, not the chat panel.
    // Queried by the dialog's own Accept button: the page card behind it is also titled
    // "Talk to Meera", so matching that text alone passes before the dialog has opened.
    expect(await screen.findByRole('button', { name: 'Accept' })).toBeInTheDocument();
    expect(screen.queryByPlaceholderText(/ask meera/i)).not.toBeInTheDocument();
  });

  it('opens the chat panel after accepting consent', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByRole('button', { name: /open meera/i }));
    await user.click(await screen.findByRole('button', { name: 'Accept' }));

    // The consent dialog itself must go away (its title now matches the page card behind it,
    // so query the dialog role), and the chat panel's composer must appear.
    await waitFor(() => {
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    });
    // The composer placeholder is bilingual (Round 2 QA). The demo creator's language is
    // 'en-IN' since 2026-09-23 (English is the default), so the English one renders here;
    // the Hindi one is covered in MeeraCopilotChat's own tests.
    expect(await screen.findByPlaceholderText(/ask meera/i)).toBeInTheDocument();
  });
});
