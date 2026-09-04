/**
 * Creator Settings — Mobile Number dialog branches on ApiError.code, not the bare HTTP
 * status (PHONE-0904 Q6 fix).
 *
 * WHY THIS FILE EXISTS
 * ---------------------
 * `PATCH /me/creator-profile` is shared by this dialog (which only ever sends `{ phone }`)
 * and creator-profile.tsx's edit dialog (which can also send `username`). Both a duplicate
 * phone AND a duplicate username come back as a bare `409 CONFLICT`
 * (CreatorProfileService.java:94, :135 for USERNAME_TAKEN; UserPhoneService.java:112-126 for
 * PHONE_ALREADY_EXISTS). The pre-fix code branched on `err.status === 409` alone, so any
 * future 409 from this endpoint — including a username conflict, if this dialog or a sibling
 * caller ever starts sending one through the same catch path — would have been mislabeled
 * "This mobile number is already registered". The fix keys on the machine-readable
 * `err.code` (src/lib/api.ts:276) instead.
 *
 * HARNESS NOTES — mirrors creator-settings-change-password.test.tsx's conventions.
 *
 * Run: npx vitest run src/pages/creator-settings-phone-409.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import CreatorSettingsPage from './creator-settings';
import { ApiError } from '@/lib/api';

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

const patchMe = vi.fn();
const getMe = vi.fn();

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    isApiLive: () => true,
    api: {
      auth: {
        logout: vi.fn().mockResolvedValue({ message: 'ok' }),
        changePassword: vi.fn(),
      },
      notifications: {
        getPreferences: vi.fn().mockResolvedValue([]),
        setPreference: vi.fn().mockResolvedValue({ ok: true }),
      },
      me: { deleteAccount: vi.fn().mockResolvedValue({ ok: true }) },
      creatorProfile: {
        getMe: (...a: unknown[]) => getMe(...a),
        patchMe: (...a: unknown[]) => patchMe(...a),
      },
      creatorAgentPrefs: {
        getPreferences: vi.fn().mockRejectedValue(new Error('not under test')),
        updatePreferences: vi.fn(),
        recordConsent: vi.fn(),
        listConversations: vi.fn().mockResolvedValue({ conversations: [] }),
        exportConversation: vi.fn(),
        deleteConversation: vi.fn(),
      },
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

async function openPhoneDialog(user: ReturnType<typeof userEvent.setup>) {
  await user.click(await screen.findByText(/Add your mobile number/i));
  expect(await screen.findByRole('heading', { name: 'Mobile Number' })).toBeInTheDocument();
}

describe('CreatorSettingsPage — Mobile Number dialog keys off ApiError.code (PHONE-0904 Q6)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    getMe.mockResolvedValue({ phone: null });
  });

  it('shows the duplicate-phone message on a PHONE_ALREADY_EXISTS 409', async () => {
    patchMe.mockRejectedValue(
      new ApiError('PHONE_ALREADY_EXISTS', 'An account with this phone number already exists', 409),
    );
    const user = userEvent.setup({ delay: null });
    renderSettings();
    await openPhoneDialog(user);

    // { selector: 'input' } — the Dialog root itself is also `aria-labelledby` the "Mobile
    // Number" title text, so a plain text match on /Mobile number/i resolves to two
    // candidates (the dialog div and the actual input); scope to the input.
    await user.type(
      screen.getByLabelText(/Mobile number/i, { selector: 'input' }),
      '9876543210',
    );
    await user.click(screen.getByRole('button', { name: 'Save' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'This mobile number is already registered',
    );
    // Not treated as a generic failure toast.
    expect(toastMock).not.toHaveBeenCalledWith(
      expect.objectContaining({ title: 'Could not save changes' }),
    );
  });

  it('does NOT show the phone message on a USERNAME_TAKEN 409 — falls back to the generic toast', async () => {
    // This dialog only ever submits `{ phone }`, so USERNAME_TAKEN can't fire from here today
    // (Priya's PHONE-0904 Q6 review) — this test pins the code-based branch so that stays true
    // even if a future payload change lets it collide, rather than relying on it never
    // happening. A bare `status === 409` check would have mislabeled this.
    patchMe.mockRejectedValue(
      new ApiError('USERNAME_TAKEN', 'This username is already taken', 409),
    );
    const user = userEvent.setup({ delay: null });
    renderSettings();
    await openPhoneDialog(user);

    await user.type(
      screen.getByLabelText(/Mobile number/i, { selector: 'input' }),
      '9876543210',
    );
    await user.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() =>
      expect(toastMock).toHaveBeenCalledWith(
        expect.objectContaining({
          title: 'Could not save changes',
          description: 'This username is already taken',
        }),
      ),
    );
    expect(screen.queryByText(/This mobile number is already registered/i)).not.toBeInTheDocument();
  });
});
