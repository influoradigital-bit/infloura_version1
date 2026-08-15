/**
 * CreatorLayout — sidebar logout server invalidation (CR-90 / F-0109).
 *
 * WHY THIS FILE EXISTS
 * --------------------
 * The sidebar/mobile-dropdown "Log out" path — the primary, easiest-to-reach way a
 * creator logs out — used to clear only client-side state (`logout()` +
 * `clearCreatorSession()`) and never called the server. The HttpOnly refresh
 * cookie / refresh token stayed live, so a creator who "logged out" this way could
 * silently get a new access token via `/auth/refresh`. The fix calls
 * `api.auth.logout('creator')` first (mirroring the already-reviewed Settings-page
 * pattern, CR-91/CR-32), then always clears locally even if the server call fails.
 *
 * Run: npx vitest run src/components/creator/creator-layout-logout.test.tsx
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { CreatorLayout } from './creator-layout';

const authLogout = vi.fn().mockResolvedValue({ message: 'ok' });
const creatorProfileGetMe = vi.fn().mockResolvedValue(null);
const dealsList = vi.fn().mockResolvedValue([]);

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    isApiLive: () => true,
    api: {
      auth: { logout: (...a: unknown[]) => authLogout(...a) },
      creatorProfile: { getMe: (...a: unknown[]) => creatorProfileGetMe(...a) },
      deals: { list: (...a: unknown[]) => dealsList(...a) },
    },
  };
});

const clearCreatorSession = vi.fn();
vi.mock('@/lib/auth-session', async () => {
  const actual = await vi.importActual<typeof import('@/lib/auth-session')>('@/lib/auth-session');
  return {
    ...actual,
    clearCreatorSession: (...a: unknown[]) => clearCreatorSession(...a),
    getCreatorSession: () => null,
  };
});

const navigateMock = vi.fn();
vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom');
  return {
    ...actual,
    useNavigate: () => navigateMock,
  };
});

function renderShell() {
  return render(
    <MemoryRouter initialEntries={['/creator/dashboard']}>
      <CreatorLayout>
        <div>page content</div>
      </CreatorLayout>
    </MemoryRouter>,
  );
}

/** Opens the sidebar account menu and clicks "Log out", then confirms in the dialog. */
async function logOutViaSidebar(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByTestId('creator-sidebar-account-trigger'));
  const menuLogout = await screen.findAllByText('Log out');
  // The dropdown item, not yet the confirm-dialog action — click the first hit.
  await user.click(menuLogout[0]);
  const dialogAction = await screen.findByRole('button', { name: 'Log out' });
  await user.click(dialogAction);
}

describe('CreatorLayout — sidebar logout (CR-90 / F-0109)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    // No creator_token in localStorage → identity/unread-count hooks stay inert,
    // matching this repo's existing creator-shell test harness convention.
    localStorage.clear();
  });

  afterEach(() => {
    localStorage.clear();
  });

  it('calls the server logout endpoint before clearing local session state', async () => {
    const user = userEvent.setup();
    renderShell();

    await logOutViaSidebar(user);

    await waitFor(() => expect(authLogout).toHaveBeenCalledWith('creator'));
    await waitFor(() => expect(clearCreatorSession).toHaveBeenCalled());
    await waitFor(() => expect(navigateMock).toHaveBeenCalledWith('/creator/login'));

    // Regression guard: the server call must not be dropped even if a future
    // refactor reorders these — asserting call ORDER, not just that both happened.
    const logoutCallOrder = authLogout.mock.invocationCallOrder[0];
    const clearCallOrder = clearCreatorSession.mock.invocationCallOrder[0];
    expect(logoutCallOrder).toBeLessThan(clearCallOrder);
  });

  it('still clears local session and navigates away even when the server call fails', async () => {
    authLogout.mockRejectedValueOnce(new Error('network down'));
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});
    const user = userEvent.setup();
    renderShell();

    await logOutViaSidebar(user);

    await waitFor(() => expect(authLogout).toHaveBeenCalledWith('creator'));
    await waitFor(() => expect(clearCreatorSession).toHaveBeenCalled());
    await waitFor(() => expect(navigateMock).toHaveBeenCalledWith('/creator/login'));
    expect(consoleError).toHaveBeenCalled();

    consoleError.mockRestore();
  });
});
