/**
 * Creator Settings — logout clears the WHOLE creator session (CR-32 call site).
 *
 * WHY THIS FILE EXISTS
 * --------------------
 * There are two creator logout paths. CR-06 fixed the first (`creator-layout.tsx`'s sidebar
 * menu). The second — Settings → Danger Zone → Logout — kept doing
 * `logout(); localStorage.removeItem('creator_token')`, so `creator_user_id`, `creator_email`
 * and `creator_display_name` survived the logout. `persistCreatorSession` writes the display
 * name only `if (displayName)`, so the NEXT creator to sign in on that browser without one
 * inherited the previous creator's name in the shell — permanently if `/me/creator-profile`
 * then failed. That is an identity leak, not a cosmetic bug.
 *
 * CR-32's own note recorded the gap this file closes:
 * `src/lib/__tests__/creator-session.test.ts` pins the *helper* invariant (whatever
 * `persistCreatorSession` writes, `clearCreatorSession` removes), and reverting
 * `creator-settings.tsx` to the bare `removeItem` left every one of those tests green. The
 * call site was unpinned. This is the tripwire for the call site.
 *
 * REVERT-PROVEN, not merely green. With `handleLogout` reverted to
 * `logout(); localStorage.removeItem('creator_token');`, the three behavioural tests below
 * fail on the surviving keys (`creator_user_id` / `creator_email` / `creator_display_name` /
 * `creator_onboarding_completed`); restoring `clearCreatorSession()` makes them pass again.
 * Per CR-28/CR-29, that revert — not the passing run — is the evidence.
 *
 * HARNESS NOTES
 * -------------
 * - `@/lib/auth-session` is deliberately NOT mocked. Asserting "clearCreatorSession was called"
 *   would pin the name of a helper; asserting on real `localStorage` pins the outcome the
 *   creator actually experiences, and survives the helper being renamed or inlined.
 * - `CreatorLayout` is stubbed. It carries the OTHER logout path (which already calls
 *   `clearCreatorSession`), so leaving it real would make it possible for these assertions to
 *   pass for the wrong reason — and it would drag in `useCreatorIdentity` /
 *   `useCreatorUnreadCount` fetches this test has no use for.
 * - The `api` mock is wide but shallow (the `creator-chat-refresh.test.tsx` convention):
 *   members this test never exercises resolve empty rather than being omitted, because an
 *   omitted member throws on property access and the failure then reads as something unrelated.
 *
 * Run: npx vitest run src/pages/creator-settings-logout.test.tsx
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { getCreatorSession, persistCreatorSession } from '@/lib/auth-session';
import CreatorSettingsPage from './creator-settings';

const toastMock = vi.fn();
vi.mock('@/hooks/use-toast', () => ({
  useToast: () => ({ toast: (...a: unknown[]) => toastMock(...a) }),
  toast: (...a: unknown[]) => toastMock(...a),
}));

/** Zustand's in-memory user drop. Local storage is the thing under test, not this. */
const storeLogout = vi.fn();
vi.mock('@/lib/store', () => ({
  useAuthStore: () => ({
    user: { id: 'cr_1', displayName: 'Tejas Creater', email: 'tejas@example.com', role: 'creator' },
    logout: storeLogout,
  }),
}));

// See HARNESS NOTES — the shell owns the other logout path, so it must not be in the picture.
vi.mock('@/components/creator/creator-layout', () => ({
  CreatorLayout: ({ children }: { children: React.ReactNode }) => (
    <div data-testid="creator-layout">{children}</div>
  ),
}));

const authLogout = vi.fn();
const apiLive = vi.fn();

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    isApiLive: () => apiLive(),
    api: {
      auth: { logout: (...a: unknown[]) => authLogout(...a) },
      // Mounted-on-load: the page reads the global email preference in an effect.
      notifications: {
        getPreferences: vi.fn().mockResolvedValue([]),
        setPreference: vi.fn().mockResolvedValue({ ok: true }),
      },
      // Not exercised here (Delete Account has its own dialog) but reachable from this page —
      // present so a stray property access can't fail as something unrelated.
      me: { deleteAccount: vi.fn().mockResolvedValue({ ok: true }) },
      creatorProfile: { getMe: vi.fn().mockResolvedValue(null) },
    },
  };
});

/** Every key a real creator login leaves behind, per `persistCreatorSession` + the token. */
function seedCreatorSession() {
  localStorage.setItem('creator_token', 'access.token.value');
  persistCreatorSession({
    user: { id: 'cr_1', email: 'tejas@example.com', displayName: 'Tejas Creater' },
    accessToken: 'access.token.value',
    onboardingCompleted: true,
  });
}

const creatorKeys = () => Object.keys(localStorage).filter((k) => k.startsWith('creator_')).sort();

function renderSettings() {
  return render(
    <MemoryRouter initialEntries={['/creator/settings']}>
      <Routes>
        <Route path="/creator/settings" element={<CreatorSettingsPage />} />
        <Route path="/creator/login" element={<div>creator login screen</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

/** Danger Zone → Logout → confirm in the AlertDialog. */
async function logOutFromSettings(user: ReturnType<typeof userEvent.setup>) {
  await user.click(await screen.findByRole('button', { name: 'Logout' }));
  const dialog = await screen.findByRole('alertdialog');
  expect(within(dialog).getByText('Confirm Logout')).toBeInTheDocument();
  await user.click(within(dialog).getByRole('button', { name: 'Logout' }));
}

describe('CreatorSettingsPage — logout clears the full creator session (CR-32)', () => {
  let consoleError: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    apiLive.mockReturnValue(true);
    authLogout.mockResolvedValue({ message: 'ok' });
    consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});
  });

  afterEach(() => {
    consoleError.mockRestore();
  });

  it('renders the Danger Zone logout control', async () => {
    seedCreatorSession();
    renderSettings();

    // Sanity check on the harness itself — if this fails, the assertions below are testing
    // nothing and would pass for the wrong reason.
    expect(await screen.findByRole('heading', { name: 'Settings' })).toBeInTheDocument();
    expect(screen.getByText('Danger Zone')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Logout' })).toBeInTheDocument();
  });

  it('removes every creator_* key, not just the token', async () => {
    const user = userEvent.setup();
    seedCreatorSession();
    // Sanity: the keys really are there, so the assertion below cannot pass vacuously.
    expect(creatorKeys()).toEqual([
      'creator_display_name',
      'creator_email',
      'creator_onboarding_completed',
      'creator_token',
      'creator_user_id',
    ]);

    renderSettings();
    await logOutFromSettings(user);

    // THE tripwire. A `removeItem('creator_token')`-only logout leaves the other four behind.
    await waitFor(() => expect(creatorKeys()).toEqual([]));
    // Named individually so a failure says WHICH identity field leaked, rather than just
    // "arrays differ" — `creator_display_name` is the one a second creator actually sees.
    expect(localStorage.getItem('creator_display_name')).toBeNull();
    expect(localStorage.getItem('creator_email')).toBeNull();
    expect(localStorage.getItem('creator_user_id')).toBeNull();
    expect(localStorage.getItem('creator_onboarding_completed')).toBeNull();
    expect(getCreatorSession()).toBeNull();

    // The server session is ended too, and the store user is dropped — this path must not
    // regress into a local-only logout.
    expect(authLogout).toHaveBeenCalledWith('creator');
    expect(storeLogout).toHaveBeenCalled();
    await waitFor(() => expect(screen.getByText('creator login screen')).toBeInTheDocument());
  });

  it('does not leave a name for the next creator to inherit', async () => {
    const user = userEvent.setup();
    seedCreatorSession();
    renderSettings();
    await logOutFromSettings(user);
    await waitFor(() => expect(getCreatorSession()).toBeNull());

    // The exact CR-32 symptom, stated at the level a person would report it: creator #2 signs
    // in on this browser with no display name on their token pair. `persistCreatorSession`
    // writes that key only `if (displayName)`, so anything the logout failed to remove shows
    // through as creator #1's name in the shell.
    persistCreatorSession({
      user: { id: 'cr_2', email: 'second@example.com' },
      accessToken: 'second.token.value',
      onboardingCompleted: true,
    });

    expect(getCreatorSession()).toMatchObject({ userId: 'cr_2', email: 'second@example.com' });
    expect(getCreatorSession()?.displayName).toBeUndefined();
    expect(localStorage.getItem('creator_display_name')).toBeNull();
  });

  it('still clears the local session when the server logout call fails', async () => {
    // A 500 from POST /auth/logout must not strand the creator in a logged-in-looking UI with
    // their identity still in localStorage — the failure path is the one most likely to be
    // "fixed" later by trimming the clear back down.
    const user = userEvent.setup();
    authLogout.mockRejectedValue(new Error('network down'));
    seedCreatorSession();
    renderSettings();
    await logOutFromSettings(user);

    await waitFor(() => expect(creatorKeys()).toEqual([]));
    expect(getCreatorSession()).toBeNull();
    expect(toastMock).toHaveBeenCalledWith(
      expect.objectContaining({ title: 'Logged out locally', variant: 'destructive' }),
    );
    await waitFor(() => expect(screen.getByText('creator login screen')).toBeInTheDocument());
  });
});
