/**
 * Photo check inside Meera (SPEC §4, 2026-09-26) — the old /creator/shoot-check link and the
 * `?camera=1` param on the Meera page (/creator/copilot).
 *
 * - The route redirects to /creator/copilot?camera=1 (checked through the REAL App router below,
 *   not a copy of the route table).
 * - The page opens Meera through the same consent-checked path as "Open Meera": a creator who has
 *   not consented sees the consent screen first, and accepting opens the chat with the camera ask
 *   kept. The chat receives `openCameraOnMount` and opens its own camera sheet once connected.
 * - The param is removed (replace) once spent, so a reload does not reopen the camera; Decline
 *   and a disabled feature spend it too.
 * - A plain "Open Meera" never opens the camera, including after a declined camera ask.
 * - The page is titled "Meera" / "Your personal manager" (RULINGS 3: no "Co-pilot" in the UI).
 *
 * MeeraCopilotChat is replaced by a probe that shows the props this page hands it; the camera
 * sheet itself belongs to the chat and is tested there.
 *
 * Run: npx vitest run src/pages/creator-copilot.camera-param.test.tsx
 */
import * as React from 'react';
import { describe, it, expect, vi, beforeAll, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useLocation } from 'react-router-dom';
import type { CreatorAgentPreferences } from '@/lib/api';
import CreatorCopilotPage from './creator-copilot';

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
vi.mock('@/components/creator/challenge/ChallengeCard', () => ({
  ChallengeCard: () => <div data-testid="challenge-card" />,
}));
vi.mock('@/components/creator/copilot/PasteBriefCard', () => ({
  PasteBriefCard: () => <div data-testid="paste-brief-card" />,
}));
vi.mock('@/components/creator/credits/HeroCreditsChip', () => ({
  HeroCreditsChip: () => null,
}));
vi.mock('@/components/creator/MeeraCopilotChat', () => ({
  MeeraCopilotChat: (props: {
    openCameraOnMount?: boolean;
    onClose: () => void;
    onConsentRequired: () => void;
  }) => (
    <div data-testid="meera-chat" data-open-camera={String(props.openCameraOnMount === true)}>
      <button type="button" onClick={props.onClose}>
        Close chat probe
      </button>
      <button type="button" onClick={props.onConsentRequired}>
        Consent refused probe
      </button>
    </div>
  ),
}));

const { getPreferences, recordConsent } = vi.hoisted(() => ({
  getPreferences: vi.fn(),
  recordConsent: vi.fn(),
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
    },
  };
});

function prefs(consent: boolean): CreatorAgentPreferences {
  return { creator_language: 'en-IN', consent_accepted: consent } as unknown as CreatorAgentPreferences;
}

function LocationProbe() {
  const { pathname, search } = useLocation();
  return <div data-testid="location">{`${pathname}${search}`}</div>;
}

function renderPage(entry: string) {
  return render(
    <MemoryRouter initialEntries={[entry]}>
      <CreatorCopilotPage />
      <LocationProbe />
    </MemoryRouter>,
  );
}

const location = () => screen.getByTestId('location').textContent;

beforeEach(() => {
  getPreferences.mockReset();
  recordConsent.mockReset().mockResolvedValue(undefined);
});

describe('Meera page — title (RULINGS 3)', () => {
  it('is titled "Meera" with the subtitle "Your personal manager", and never says Co-pilot', async () => {
    getPreferences.mockResolvedValue(prefs(true));
    renderPage('/creator/copilot');

    expect(screen.getByRole('heading', { level: 1, name: 'Meera' })).toBeInTheDocument();
    expect(screen.getByText('Your personal manager')).toBeInTheDocument();
    await waitFor(() => expect(getPreferences).toHaveBeenCalled());
    // The page's own text (the location probe beside it prints the /creator/copilot route).
    expect(screen.getByTestId('creator-layout').textContent).not.toMatch(/co-?pilot/i);
  });
});

describe('Meera page — ?camera=1', () => {
  it('consented: opens the chat with the camera ask, then drops the param (replace)', async () => {
    getPreferences.mockResolvedValue(prefs(true));
    renderPage('/creator/copilot?camera=1');

    const chat = await screen.findByTestId('meera-chat');
    expect(chat).toHaveAttribute('data-open-camera', 'true');
    await waitFor(() => expect(location()).toBe('/creator/copilot'));
    // Dropping the param must not re-arm the ask or close the chat.
    expect(screen.getByTestId('meera-chat')).toHaveAttribute('data-open-camera', 'true');
  });

  it('not consented: the consent screen comes first, and Accept opens the chat with the camera', async () => {
    const user = userEvent.setup();
    getPreferences.mockResolvedValue(prefs(false));
    renderPage('/creator/copilot?camera=1');

    expect(await screen.findByRole('button', { name: 'Accept' })).toBeInTheDocument();
    expect(screen.queryByTestId('meera-chat')).not.toBeInTheDocument();
    // Not spent yet: the camera ask is still pending behind the consent screen.
    expect(location()).toBe('/creator/copilot?camera=1');

    await user.click(screen.getByRole('button', { name: 'Accept' }));

    expect(recordConsent).toHaveBeenCalledTimes(1);
    const chat = await screen.findByTestId('meera-chat');
    expect(chat).toHaveAttribute('data-open-camera', 'true');
    await waitFor(() => expect(location()).toBe('/creator/copilot'));
  });

  it('Decline: no chat, the param is dropped, and a later "Open Meera" does not open the camera', async () => {
    const user = userEvent.setup();
    getPreferences.mockResolvedValue(prefs(false));
    renderPage('/creator/copilot?camera=1');

    await user.click(await screen.findByRole('button', { name: 'Not now' }));
    await waitFor(() => expect(location()).toBe('/creator/copilot'));
    expect(screen.queryByTestId('meera-chat')).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /open meera/i }));
    await user.click(await screen.findByRole('button', { name: 'Accept' }));
    expect(await screen.findByTestId('meera-chat')).toHaveAttribute('data-open-camera', 'false');
  });

  it('feature disabled: only the page shows, and the param is dropped', async () => {
    const { ApiError } = await import('@/lib/api');
    getPreferences.mockRejectedValue(new ApiError('FEATURE_DISABLED', 'Meera is off', 404));
    renderPage('/creator/copilot?camera=1');

    expect(await screen.findByText(/isn't available on your account yet/i)).toBeInTheDocument();
    await waitFor(() => expect(location()).toBe('/creator/copilot'));
    expect(screen.queryByTestId('meera-chat')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Accept' })).not.toBeInTheDocument();
  });
});

describe('Meera page — no param', () => {
  it('"Open Meera" opens the chat without the camera', async () => {
    const user = userEvent.setup();
    getPreferences.mockResolvedValue(prefs(true));
    renderPage('/creator/copilot');

    await user.click(screen.getByRole('button', { name: /open meera/i }));
    expect(await screen.findByTestId('meera-chat')).toHaveAttribute('data-open-camera', 'false');
  });

  it('closing a camera-opened chat and reopening it with "Open Meera" does not reopen the camera', async () => {
    const user = userEvent.setup();
    getPreferences.mockResolvedValue(prefs(true));
    renderPage('/creator/copilot?camera=1');

    expect(await screen.findByTestId('meera-chat')).toHaveAttribute('data-open-camera', 'true');
    await waitFor(() => expect(location()).toBe('/creator/copilot'));
    await user.click(screen.getByRole('button', { name: 'Close chat probe' }));
    expect(screen.queryByTestId('meera-chat')).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /open meera/i }));
    expect(await screen.findByTestId('meera-chat')).toHaveAttribute('data-open-camera', 'false');
  });
});

describe('Meera page — a camera ask never leaks into another entry', () => {
  it('declined camera ask, then "Ask Meera" through consent: the chat opens without the camera', async () => {
    const user = userEvent.setup();
    getPreferences.mockResolvedValue(prefs(false));
    renderPage('/creator/copilot?camera=1');

    await user.click(await screen.findByRole('button', { name: 'Not now' }));
    await waitFor(() => expect(location()).toBe('/creator/copilot'));

    await user.type(screen.getByPlaceholderText(/type a question for meera/i), 'What should I post?{Enter}');
    await user.click(await screen.findByRole('button', { name: 'Accept' }));
    expect(await screen.findByTestId('meera-chat')).toHaveAttribute('data-open-camera', 'false');
  });

  it('camera chat closed, then "Ask Meera" (consented): the chat opens without the camera', async () => {
    const user = userEvent.setup();
    getPreferences.mockResolvedValue(prefs(true));
    renderPage('/creator/copilot?camera=1');

    expect(await screen.findByTestId('meera-chat')).toHaveAttribute('data-open-camera', 'true');
    await user.click(screen.getByRole('button', { name: 'Close chat probe' }));

    await user.type(screen.getByPlaceholderText(/type a question for meera/i), 'What should I post?{Enter}');
    expect(await screen.findByTestId('meera-chat')).toHaveAttribute('data-open-camera', 'false');
  });

  it('consent refused mid-session on a camera chat: Accept reopens it with the camera still asked for', async () => {
    const user = userEvent.setup();
    getPreferences.mockResolvedValue(prefs(true));
    renderPage('/creator/copilot?camera=1');

    expect(await screen.findByTestId('meera-chat')).toHaveAttribute('data-open-camera', 'true');
    await user.click(screen.getByRole('button', { name: 'Consent refused probe' }));
    await user.click(await screen.findByRole('button', { name: 'Accept' }));
    expect(await screen.findByTestId('meera-chat')).toHaveAttribute('data-open-camera', 'true');
  });
});

describe('Meera page — StrictMode', () => {
  it('one arrival of ?camera=1 opens Meera once, even with StrictMode running effects twice', async () => {
    getPreferences.mockResolvedValue(prefs(false));
    const strict = (entry: string) =>
      render(
        <React.StrictMode>
          <MemoryRouter initialEntries={[entry]}>
            <CreatorCopilotPage />
            <LocationProbe />
          </MemoryRouter>
        </React.StrictMode>,
      );

    // Baseline: the page's own mount probe, however many times StrictMode runs it.
    const plain = strict('/creator/copilot');
    await waitFor(() => expect(getPreferences).toHaveBeenCalled());
    await new Promise((r) => setTimeout(r, 50));
    const probeCalls = getPreferences.mock.calls.length;
    plain.unmount();
    getPreferences.mockClear();

    strict('/creator/copilot?camera=1');
    expect(await screen.findByRole('button', { name: 'Accept' })).toBeInTheDocument();
    await new Promise((r) => setTimeout(r, 50));
    expect(getPreferences.mock.calls.length - probeCalls).toBe(1);
  });
});

// The redirect through the real route table. Importing `@/App` pulls the whole route graph, so the
// import is deferred and given its own hook timeout (same as src/__tests__/creator-protected-route
// .test.tsx, which also stubs matchMedia for GSAP's import-time call).
describe('/creator/shoot-check (real App router)', () => {
  let App: typeof import('@/App').default;

  beforeAll(async () => {
    if (!window.matchMedia) {
      window.matchMedia = (query: string) =>
        ({
          matches: false,
          media: query,
          onchange: null,
          addListener: () => {},
          removeListener: () => {},
          addEventListener: () => {},
          removeEventListener: () => {},
          dispatchEvent: () => false,
        }) as unknown as MediaQueryList;
    }
    ({ default: App } = await import('@/App'));
  }, 120_000);

  afterEach(() => {
    localStorage.clear();
    sessionStorage.clear();
    window.history.replaceState({}, '', '/');
  });

  it('lands on the Meera page with the camera ask, which then opens the chat and leaves the URL', async () => {
    const { api } = await import('@/lib/api');
    api.auth.setToken('creator', 'creator-tok', true);
    getPreferences.mockResolvedValue(prefs(true));
    window.history.pushState({}, '', '/creator/shoot-check');

    render(<App />);

    const chat = await screen.findByTestId('meera-chat', {}, { timeout: 10_000 });
    expect(chat).toHaveAttribute('data-open-camera', 'true');
    expect(screen.getByRole('heading', { level: 1, name: 'Meera' })).toBeInTheDocument();
    await waitFor(() => expect(`${window.location.pathname}${window.location.search}`).toBe('/creator/copilot'));
  }, 30_000);
});
