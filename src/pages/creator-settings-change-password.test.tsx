/**
 * Creator Settings — Change Password dialog is wired to a real endpoint (CR-87 / F-0108).
 *
 * WHY THIS FILE EXISTS
 * --------------------
 * The dialog's three inputs were uncontrolled (no value/onChange) and "Update Password" had
 * no onClick at all, even though `api.auth.changePassword` (POST /me/password) is a real,
 * working, rate-limited backend endpoint. A creator had no way to change their password
 * anywhere in the product. The fix wires the inputs to state, validates client-side
 * (non-empty, length, confirmation match), calls the endpoint, and surfaces the server's own
 * error message on failure (e.g. wrong current password) rather than a generic one.
 *
 * HARNESS NOTES — mirrors creator-settings-logout.test.tsx's conventions.
 *
 * Run: npx vitest run src/pages/creator-settings-change-password.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import CreatorSettingsPage from './creator-settings';

const toastMock = vi.fn();
vi.mock('@/hooks/use-toast', () => ({
  useToast: () => ({ toast: (...a: unknown[]) => toastMock(...a) }),
  toast: (...a: unknown[]) => toastMock(...a),
}));

vi.mock('@/lib/store', () => ({
  useAuthStore: () => ({
    user: { id: 'cr_1', displayName: 'Tejas Creater', email: 'tejas@example.com', role: 'creator' },
    logout: vi.fn(),
  }),
}));

vi.mock('@/components/creator/creator-layout', () => ({
  CreatorLayout: ({ children }: { children: React.ReactNode }) => (
    <div data-testid="creator-layout">{children}</div>
  ),
}));

const changePassword = vi.fn();
const apiLive = vi.fn();

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    isApiLive: () => apiLive(),
    api: {
      auth: {
        logout: vi.fn().mockResolvedValue({ message: 'ok' }),
        changePassword: (...a: unknown[]) => changePassword(...a),
      },
      notifications: {
        getPreferences: vi.fn().mockResolvedValue([]),
        setPreference: vi.fn().mockResolvedValue({ ok: true }),
      },
      me: { deleteAccount: vi.fn().mockResolvedValue({ ok: true }) },
      creatorProfile: { getMe: vi.fn().mockResolvedValue(null) },
      // CR-101/F-0114 — ConnectedAccounts is now mounted on this page; its useMetaConnection
      // hook needs both members present or property access on the mock throws.
      metaOAuth: {
        getLocalConnectionState: vi.fn().mockReturnValue({ connected: false, scopes: [], accountType: null }),
        status: vi.fn().mockResolvedValue({ connected: false, grantedScopes: [] }),
        setLocalConnectionState: vi.fn(),
        authorize: vi.fn(),
      },
    },
  };
});

function renderSettings() {
  return render(
    <MemoryRouter initialEntries={['/creator/settings']}>
      <Routes>
        <Route path="/creator/settings" element={<CreatorSettingsPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

async function openPasswordDialog(user: ReturnType<typeof userEvent.setup>) {
  await user.click(await screen.findByText('Change Password'));
  expect(await screen.findByRole('heading', { name: 'Change Password' })).toBeInTheDocument();
}

async function fillPasswordForm(
  user: ReturnType<typeof userEvent.setup>,
  { current = 'oldpass123', next = 'newpass456', confirm = 'newpass456' } = {},
) {
  if (current) await user.type(screen.getByLabelText('Current Password'), current);
  if (next) await user.type(screen.getByLabelText('New Password'), next);
  if (confirm) await user.type(screen.getByLabelText('Confirm New Password'), confirm);
}

describe('CreatorSettingsPage — Change Password (CR-87 / F-0108)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    apiLive.mockReturnValue(true);
    changePassword.mockResolvedValue({ changed: true });
  });

  it('submits the entered passwords to api.auth.changePassword and confirms success', async () => {
    const user = userEvent.setup({ delay: null });
    renderSettings();
    await openPasswordDialog(user);
    await fillPasswordForm(user);

    await user.click(screen.getByRole('button', { name: 'Update Password' }));

    // CR-87/Priya review blocker 1: must be called with the 'creator' role, not the http
    // layer's 'brand' default — a creator-scoped request must carry the creator_token.
    await waitFor(() =>
      expect(changePassword).toHaveBeenCalledWith('creator', {
        currentPassword: 'oldpass123',
        newPassword: 'newpass456',
      }),
    );
    await waitFor(() =>
      expect(toastMock).toHaveBeenCalledWith(
        expect.objectContaining({ title: 'Password updated' }),
      ),
    );
    // Dialog closes and fields are cleared on success — a re-open must not show stale values.
    await waitFor(() =>
      expect(screen.queryByRole('heading', { name: 'Change Password' })).not.toBeInTheDocument(),
    );
  });

  it('rejects a submit with any field left empty, client-side, without calling the API', async () => {
    // CR-87/Priya review blocker 2: this clause had no test — the guard worked but was
    // unprotected. Empty current-password specifically, since that's the field most likely to
    // be skipped by a password-manager autofill mismatch.
    const user = userEvent.setup({ delay: null });
    renderSettings();
    await openPasswordDialog(user);
    await fillPasswordForm(user, { current: '' });

    await user.click(screen.getByRole('button', { name: 'Update Password' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(/all fields are required/i);
    expect(changePassword).not.toHaveBeenCalled();
  });

  it('rejects mismatched new/confirm passwords client-side without calling the API', async () => {
    const user = userEvent.setup({ delay: null });
    renderSettings();
    await openPasswordDialog(user);
    await fillPasswordForm(user, { next: 'newpass456', confirm: 'different789' });

    await user.click(screen.getByRole('button', { name: 'Update Password' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(/do not match/i);
    expect(changePassword).not.toHaveBeenCalled();
  });

  it('rejects a too-short new password client-side without calling the API', async () => {
    const user = userEvent.setup({ delay: null });
    renderSettings();
    await openPasswordDialog(user);
    await fillPasswordForm(user, { next: 'short', confirm: 'short' });

    await user.click(screen.getByRole('button', { name: 'Update Password' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(/at least 8 characters/i);
    expect(changePassword).not.toHaveBeenCalled();
  });

  it('surfaces the server error message on a rejected current password, and keeps the dialog open', async () => {
    changePassword.mockRejectedValue(new Error('Current password is incorrect'));
    const user = userEvent.setup({ delay: null });
    renderSettings();
    await openPasswordDialog(user);
    await fillPasswordForm(user);

    await user.click(screen.getByRole('button', { name: 'Update Password' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Current password is incorrect');
    // Must not have been treated as success.
    expect(toastMock).not.toHaveBeenCalledWith(
      expect.objectContaining({ title: 'Password updated' }),
    );
    expect(screen.getByRole('heading', { name: 'Change Password' })).toBeInTheDocument();
  });
});
