/**
 * U-2 (SPEC.md §8.5) — where `PasteBriefCard` is mounted on `/creator/copilot`, and how it shares
 * the page's feature-flag and consent gates.
 *
 *   1. Mounted when Meera is enabled, absent in the calm FEATURE_DISABLED state.
 *   2. Consent known missing → pressing Analyse opens the consent screen and sends NOTHING.
 *      Accepting from the paste card must not open the chat the creator never asked for.
 *
 * U-5 (RULINGS-U-0917.md R-U1) — "Ask Meera about this brief" opens the chat with the prompt
 * FILLED IN, never sent:
 *   3. Consent known: opens the chat with the prompt containing the literal brief id; nothing is
 *      sent (asserted both against the visible composer/turns and against `meeraApi.sendTurn`).
 *   4. The chat already open with typed text: that text stays, the prompt is appended.
 *   5. Consent known missing: the consent screen opens; Accept opens the chat with the prompt
 *      filled in; Decline opens nothing, and the prompt does not leak into a later plain
 *      "Open Meera".
 *
 * Run: npx vitest run src/pages/creator-copilot-paste-brief.test.tsx
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import type { BriefAnalysisResponse, CreatorAgentPreferences } from '@/lib/api';

vi.mock('@/components/creator/creator-layout', () => ({
  CreatorLayout: ({ children }: { children: React.ReactNode }) => (
    <div data-testid="creator-layout">{children}</div>
  ),
}));
vi.mock('@/components/creator/copilot/DailySuggestionSection', () => ({
  DailySuggestionSection: () => <div data-testid="daily-suggestion-section" />,
}));
vi.mock('@/components/creator/copilot/CopilotPreviewCard', () => ({
  CopilotPreviewCard: () => null,
}));
vi.mock('@/hooks/useDailySuggestion', () => ({
  useDailySuggestion: () => ({ status: 'idle' }),
}));

const { getPreferences, recordConsent, pasteMock, sendTurnMock } = vi.hoisted(() => ({
  getPreferences: vi.fn(),
  recordConsent: vi.fn(),
  pasteMock: vi.fn(),
  sendTurnMock: vi.fn(),
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
        recordConsent: (...args: unknown[]) => recordConsent(...args),
      },
      creatorBriefs: {
        ...actual.api.creatorBriefs,
        paste: (...args: unknown[]) => pasteMock(...args),
      },
    },
  };
});

// U-5 — a spy on the real send path. `isApiLive()` is false in this test env (no VITE_API_MODE),
// so MeeraCopilotChat never actually calls this; the spy is the belt to the visible-DOM braces:
// if a future change made mock mode call it, or the app ran live, this must still read zero.
vi.mock('@/lib/meera-api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/meera-api')>('@/lib/meera-api');
  return {
    ...actual,
    meeraApi: {
      ...actual.meeraApi,
      sendTurn: (...args: unknown[]) => sendTurnMock(...args),
    },
  };
});

import { ApiError } from '@/lib/api';
import CreatorCopilotPage from './creator-copilot';

const PREFS: CreatorAgentPreferences = {
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
  rate_card_shareable: false,
  rate_card: null,
  negotiation_holdout: false,
  approved_draft_count: 0,
  level_up_eligible: false,
};

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/creator/copilot']}>
      <CreatorCopilotPage />
    </MemoryRouter>,
  );
}

function analysis(overrides: Partial<BriefAnalysisResponse> = {}): BriefAnalysisResponse {
  return {
    brief_id: 'brief_u5',
    source: 'PASTED',
    status: 'ANALYZED',
    extraction: {
      budget_stated: false,
      barter_only: false,
      usage_perpetual: false,
      off_platform_payment_hint: false,
      disclosure_hidden_hint: false,
      vague_deliverables: false,
    },
    flags: [],
    quote: {
      bundle_discount: '0',
      bundle_discount_value: 0,
      total: '4,000',
      total_value: 4000,
      floor_total: '3,000',
      floor_total_value: 3000,
      currency: 'INR',
      payment_schedule: '50% upfront, 50% on delivery',
      revision_rounds: 1,
      provenance: 'your last 2 priced deals',
      provenance_sample_size: 2,
      withheld: false,
    },
    extraction_source: 'AI',
    ...overrides,
  };
}

/** Pastes a brief and clicks Analyse, landing on the rendered `BriefCard` with its "Ask Meera
 *  about this brief" action. Assumes `getPreferences`/`pasteMock` are already set up. */
async function pasteAndAnalyse(user: ReturnType<typeof userEvent.setup>) {
  await waitFor(() => expect(screen.getByLabelText('Brief text')).toBeInTheDocument());
  fireEvent.change(screen.getByLabelText('Brief text'), { target: { value: 'A brief worth reading.' } });
  await user.click(screen.getByRole('button', { name: 'Analyse with Meera' }));
  return screen.findByTestId('ask-meera-about-brief');
}

/**
 * Asserts the prompt is sitting in the composer, UNSENT — checked against the visible turn
 * count, not just the textbox value, because a buggy auto-send clears the draft asynchronously
 * (a `setTimeout`/promise chain), and a bare synchronous read of the textbox can win the race and
 * pass even when a send is already in flight. The short wait gives that timer a chance to fire
 * before the turn count is read, which is what actually catches it.
 */
async function expectPromptUnsent(textbox: HTMLElement, expectedValue: string) {
  await new Promise((resolve) => setTimeout(resolve, 20));
  expect(textbox).toHaveValue(expectedValue);
  // Only the local greeting turn — a real or accidental send appends a NEW 'creator' turn here.
  expect(screen.getAllByTestId('chat-turn')).toHaveLength(1);
  expect(sendTurnMock).not.toHaveBeenCalled();
}

describe('CreatorCopilotPage — PasteBriefCard mount (U-2)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    recordConsent.mockResolvedValue({ consent_accepted_at: '2026-09-17T10:00:00Z' });
  });

  it('mounts the paste card when Meera is enabled', async () => {
    getPreferences.mockResolvedValue({ ...PREFS, consent_accepted: true });
    renderPage();
    await waitFor(() => expect(getPreferences).toHaveBeenCalled());
    expect(screen.getByTestId('paste-brief-card')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Analyse with Meera' })).toBeInTheDocument();
  });

  it('does not mount it in the calm FEATURE_DISABLED state', async () => {
    getPreferences.mockRejectedValue(new ApiError('FEATURE_DISABLED', 'off', 404));
    renderPage();
    await waitFor(() =>
      expect(screen.getByText(/isn't available on your account yet/i)).toBeInTheDocument(),
    );
    expect(screen.queryByTestId('paste-brief-card')).not.toBeInTheDocument();
  });

  it('asks for consent instead of sending the paste, and accepting does not open the chat', async () => {
    getPreferences.mockResolvedValue({ ...PREFS, consent_accepted: false });
    const user = userEvent.setup();
    renderPage();
    await waitFor(() => expect(getPreferences).toHaveBeenCalled());
    // The probe's answer has to land before the click, or the card cannot know consent is missing.
    await waitFor(() =>
      expect(screen.getByLabelText('Brief text')).toBeInTheDocument(),
    );
    await new Promise((resolve) => setTimeout(resolve, 0));

    fireEvent.change(screen.getByLabelText('Brief text'), { target: { value: 'A brief.' } });
    await user.click(screen.getByRole('button', { name: 'Analyse with Meera' }));

    expect(await screen.findByText('Talk to Meera', { selector: 'h2' })).toBeInTheDocument();
    expect(pasteMock).not.toHaveBeenCalled();

    await user.click(screen.getByRole('button', { name: 'Accept' }));
    await waitFor(() => expect(recordConsent).toHaveBeenCalledTimes(1));
    await waitFor(() =>
      expect(screen.queryByText('Talk to Meera', { selector: 'h2' })).not.toBeInTheDocument(),
    );
    expect(screen.queryByPlaceholderText(/ask meera/i)).not.toBeInTheDocument();
    // The consent line clears once consent is known.
    expect(screen.queryByText(/Meera needs your consent/)).not.toBeInTheDocument();
  });

  // T1 (F-1779, PRIYA-LASTCALL-U2-0918.md item 2c) — consent already known TRUE at the page level:
  // the card sends the paste, and the SERVER's own CONSENT_REQUIRED refusal (not the client-side
  // short-circuit covered above) is what opens the consent screen, through
  // `onConsentRequired={requestConsentForPaste}` on `PasteBriefCard`.
  it('server CONSENT_REQUIRED on the paste itself opens the consent screen', async () => {
    getPreferences.mockResolvedValue({ ...PREFS, consent_accepted: true });
    pasteMock.mockRejectedValue(new ApiError('CONSENT_REQUIRED', 'Consent required.', 403));
    const user = userEvent.setup();
    renderPage();
    await waitFor(() => expect(getPreferences).toHaveBeenCalled());
    await waitFor(() => expect(screen.getByLabelText('Brief text')).toBeInTheDocument());

    fireEvent.change(screen.getByLabelText('Brief text'), { target: { value: 'A brief.' } });
    await user.click(screen.getByRole('button', { name: 'Analyse with Meera' }));

    await waitFor(() => expect(pasteMock).toHaveBeenCalled());
    expect(await screen.findByText('Talk to Meera', { selector: 'h2' })).toBeInTheDocument();
  });

  // T4 (F-1779, item 7b) — `FEATURE_DISABLED` returned by the paste call itself (the flag was
  // turned off after the page's own mount probe already said it was on), not by the mount probe
  // covered by the "does not mount it in the calm FEATURE_DISABLED state" test above.
  // `onFeatureDisabled={() => setFeatureDisabled(true)}` on `PasteBriefCard` is what this covers —
  // removing that prop, or the card's own handling of the code, must leave the card mounted with a
  // generic inline error instead of the calm disabled state.
  it('server FEATURE_DISABLED on the paste call itself unmounts the card and shows the calm disabled state', async () => {
    getPreferences.mockResolvedValue({ ...PREFS, consent_accepted: true });
    pasteMock.mockRejectedValue(new ApiError('FEATURE_DISABLED', 'off', 404));
    const user = userEvent.setup();
    renderPage();
    await waitFor(() => expect(getPreferences).toHaveBeenCalled());
    await waitFor(() => expect(screen.getByLabelText('Brief text')).toBeInTheDocument());

    fireEvent.change(screen.getByLabelText('Brief text'), { target: { value: 'A brief.' } });
    await user.click(screen.getByRole('button', { name: 'Analyse with Meera' }));

    await waitFor(() => expect(pasteMock).toHaveBeenCalled());
    await waitFor(() =>
      expect(screen.getByText(/isn't available on your account yet/i)).toBeInTheDocument(),
    );
    expect(screen.queryByTestId('paste-brief-card')).not.toBeInTheDocument();
  });
});

describe('CreatorCopilotPage — "Ask Meera about this brief" (U-5, R-U1)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    recordConsent.mockResolvedValue({ consent_accepted_at: '2026-09-17T10:00:00Z' });
  });

  it('opens the chat with the prompt filled in, containing the literal brief id, and sends nothing', async () => {
    getPreferences.mockResolvedValue({ ...PREFS, consent_accepted: true });
    pasteMock.mockResolvedValue(analysis());
    const user = userEvent.setup();
    renderPage();
    await waitFor(() => expect(getPreferences).toHaveBeenCalled());

    const askButton = await pasteAndAnalyse(user);
    await user.click(askButton);

    const textbox = await screen.findByPlaceholderText(/Ask Meera/i);
    await expectPromptUnsent(textbox, 'Look at brief brief_u5.');
  });

  it('appends to an already-open chat with typed text, never overwriting it', async () => {
    getPreferences.mockResolvedValue({ ...PREFS, consent_accepted: true });
    pasteMock.mockResolvedValue(analysis());
    const user = userEvent.setup();
    renderPage();
    await waitFor(() => expect(getPreferences).toHaveBeenCalled());

    await user.click(await screen.findByRole('button', { name: 'Open Meera' }));
    const textbox = await screen.findByPlaceholderText(/Ask Meera/i);
    await user.type(textbox, 'What did my last deal pay?');

    const askButton = await pasteAndAnalyse(user);
    await user.click(askButton);

    await expectPromptUnsent(textbox, 'What did my last deal pay? Look at brief brief_u5.');
  });

  it('consent missing at click time: opens the consent screen, and Accept opens the chat with the prompt filled in', async () => {
    getPreferences.mockResolvedValueOnce({ ...PREFS, consent_accepted: true }); // mount probe
    pasteMock.mockResolvedValue(analysis());
    const user = userEvent.setup();
    renderPage();
    await waitFor(() => expect(getPreferences).toHaveBeenCalledTimes(1));

    const askButton = await pasteAndAnalyse(user);
    getPreferences.mockResolvedValueOnce({ ...PREFS, consent_accepted: false }); // the ask's own re-probe
    await user.click(askButton);

    expect(await screen.findByText('Talk to Meera', { selector: 'h2' })).toBeInTheDocument();
    expect(screen.queryByPlaceholderText(/ask meera/i)).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Accept' }));
    await waitFor(() => expect(recordConsent).toHaveBeenCalledTimes(1));

    const textbox = await screen.findByPlaceholderText(/Ask Meera/i);
    await expectPromptUnsent(textbox, 'Look at brief brief_u5.');
  });

  it('Decline opens nothing; a later unrelated "Open Meera" (consent already known true) does not inherit the dropped prompt', async () => {
    getPreferences.mockResolvedValueOnce({ ...PREFS, consent_accepted: true }); // mount probe
    pasteMock.mockResolvedValue(analysis());
    const user = userEvent.setup();
    renderPage();
    await waitFor(() => expect(getPreferences).toHaveBeenCalledTimes(1));

    const askButton = await pasteAndAnalyse(user);
    getPreferences.mockResolvedValueOnce({ ...PREFS, consent_accepted: false }); // the ask's own re-probe
    await user.click(askButton);
    expect(await screen.findByText('Talk to Meera', { selector: 'h2' })).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Not now' }));
    await waitFor(() =>
      expect(screen.queryByText('Talk to Meera', { selector: 'h2' })).not.toBeInTheDocument(),
    );
    expect(screen.queryByPlaceholderText(/ask meera/i)).not.toBeInTheDocument();

    // A later, unrelated plain "Open Meera" must not inherit the dropped brief prompt. NOTE
    // (PRIYA-LASTCALL-U3-U5-0917.md UF5-1): this variant alone cannot falsify U5-C (Decline
    // forgetting to clear `pendingBriefPromptRef`), because a `consent_accepted: true` re-probe
    // routes through `openMeera`, which never reads that ref at all. Kept anyway as a real,
    // separate behaviour worth pinning; the actual leak path is the next test.
    getPreferences.mockResolvedValueOnce({ ...PREFS, consent_accepted: true }); // Open Meera's own re-probe
    await user.click(screen.getByRole('button', { name: 'Open Meera' }));
    const textbox = await screen.findByPlaceholderText(/Ask Meera/i);
    expect(textbox).toHaveValue('');
    expect(sendTurnMock).not.toHaveBeenCalled();
  });

  it('Decline drops the prompt: it does not survive a LATER consent round-trip (still missing, then Accept)', async () => {
    // UF5-1 (PRIYA-LASTCALL-U3-U5-0917.md) — the only code path that reads
    // `pendingBriefPromptRef` is `handleAcceptConsent`. The previous version of this test only
    // reached `openMeera` (which never reads that ref), so it could not catch U5-C. The real leak
    // path is: Ask Meera -> consent screen -> Decline -> consent STILL missing -> plain "Open
    // Meera" -> consent screen AGAIN -> Accept. If Decline forgot to clear the ref, THIS Accept
    // would silently prefill the chat with the brief prompt the creator already declined.
    getPreferences.mockResolvedValueOnce({ ...PREFS, consent_accepted: true }); // mount probe
    pasteMock.mockResolvedValue(analysis());
    const user = userEvent.setup();
    renderPage();
    await waitFor(() => expect(getPreferences).toHaveBeenCalledTimes(1));

    const askButton = await pasteAndAnalyse(user);
    getPreferences.mockResolvedValueOnce({ ...PREFS, consent_accepted: false }); // ask's own re-probe
    await user.click(askButton);
    expect(await screen.findByText('Talk to Meera', { selector: 'h2' })).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Not now' }));
    await waitFor(() =>
      expect(screen.queryByText('Talk to Meera', { selector: 'h2' })).not.toBeInTheDocument(),
    );

    // Consent is STILL missing here — a plain "Open Meera" click re-opens the consent screen,
    // not the chat.
    getPreferences.mockResolvedValueOnce({ ...PREFS, consent_accepted: false }); // Open Meera's own re-probe
    await user.click(screen.getByRole('button', { name: 'Open Meera' }));
    expect(await screen.findByText('Talk to Meera', { selector: 'h2' })).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Accept' }));
    await waitFor(() => expect(recordConsent).toHaveBeenCalledTimes(1));

    const textbox = await screen.findByPlaceholderText(/Ask Meera/i);
    expect(textbox).toHaveValue('');
    expect(sendTurnMock).not.toHaveBeenCalled();
  });
});
