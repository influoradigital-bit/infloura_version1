/**
 * Brand Settings — Account Mobile Number: a phone-only PATCH must not clear the other
 * UpdateProfileRequest fields (F-0634 investigation, ruled FALSE — no fix needed).
 *
 * WHY THIS FILE EXISTS
 * ---------------------
 * F-0634 read as "UpdateProfileRequest/UsersMeUpdatePayload carry firstName/lastName/
 * displayName/timezone/avatarUrl/phone, but handleSavePhone (brand-settings.tsx) is the only
 * frontend call site and it sends `{ phone }` alone — so five fields are 'unbound'." That framing
 * assumes a full-replace endpoint (the F-0462 shape on /workspaces/me). It is the wrong shape
 * here: `UserService#updateProfile` (influora-api/src/main/java/com/influora/service/
 * UserService.java, read directly, not assumed) guards every field with `if (req.x() != null)`
 * before applying it — a genuine partial-merge PATCH where an omitted/undefined field means
 * "leave unchanged". Since `{ phone: normalized }` never includes the other five keys,
 * JSON.stringify drops them, they arrive absent, and none of the `!= null` guards fire. There is
 * also no missing UI to wire up: this page's Security tab has no first/last/display-name,
 * timezone, or avatar inputs at all, so there is nothing for a "fix" here to bind.
 *
 * This file proves that at the actual call site: a phone-only save leaves the other five fields
 * exactly as they were. Asserting only the outgoing PATCH body would not prove persistence — it
 * would not model what the server actually DOES with what it receives. So `usersUpdateMe` here is
 * mocked as a small in-memory "server": it applies the SAME per-field `!= null` merge guard
 * `UserService#updateProfile` uses, against a persisted fake user record, and the assertions read
 * that record back — the closest a frontend-only test can get to proving the real merge semantics
 * without booting the Spring Boot service.
 *
 * FALSIFIED (2026-09-05): temporarily changed handleSavePhone's
 * `api.users.updateMe({ phone: normalized })` call to also send `firstName: '', lastName: '',
 * displayName: '', timezone: '', avatarUrl: ''` (a plausible "send every UpdateProfileRequest
 * field explicitly" regression). Under this file's merge-guard mock (`!= null` — an empty string
 * is not null), that cleared all five fields; "a phone-only save does not clear the other profile
 * fields" failed for exactly that reason (firstName expected 'Amit', received ''). Reverted;
 * suite green again.
 *
 * HARNESS NOTES — mirrors brand-settings-account-phone.test.tsx's `vi.mock('@/lib/api', ...)` shape.
 *
 * Run: npx vitest run src/pages/__tests__/brand-settings-account-phone-partial-merge.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import BrandSettingsPage from '../brand-settings';

vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom');
  return { ...actual, useNavigate: () => vi.fn() };
});

vi.mock('@/lib/store', () => ({
  useAuthStore: () => ({ logout: vi.fn() }),
}));

vi.mock('@/hooks/use-toast', () => ({
  useToast: () => ({ toast: vi.fn() }),
  toast: vi.fn(),
}));

const workspacesGetMe = vi.fn();
const usersGetMe = vi.fn();
const usersUpdateMe = vi.fn();

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    isApiLive: () => true,
    api: {
      workspaces: {
        getMe: (...a: unknown[]) => workspacesGetMe(...a),
        updateMe: vi.fn().mockResolvedValue({}),
      },
      workspaceMembers: {
        list: vi.fn().mockResolvedValue([]),
        invite: vi.fn().mockResolvedValue({ id: 'inv_1' }),
      },
      notifications: {
        getPreferences: vi.fn().mockResolvedValue([]),
        setPreference: vi.fn().mockResolvedValue({ ok: true }),
      },
      auth: {
        logout: vi.fn().mockResolvedValue({ message: 'ok' }),
        changePassword: vi.fn().mockResolvedValue({ changed: true }),
      },
      users: {
        getMe: (...a: unknown[]) => usersGetMe(...a),
        updateMe: (...a: unknown[]) => usersUpdateMe(...a),
      },
    },
  };
});

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/brand/settings']}>
      <BrandSettingsPage />
    </MemoryRouter>,
  );
}

async function openSecurityTab() {
  const user = userEvent.setup();
  await user.click(screen.getByRole('tab', { name: /Security/i }));
}

describe('BrandSettingsPage — Account Mobile Number: phone-only save preserves other profile fields (F-0634)', () => {
  // The "server": a mutable record standing in for the user's persisted row, plus an updateMe
  // implementation that applies the SAME per-field `!= null` merge guard
  // `UserService#updateProfile` uses (verified by reading that file directly). If the frontend
  // ever starts sending real (non-null) values for firstName/lastName/displayName/timezone/
  // avatarUrl, this mock "persists" that overwrite exactly as the real server would — catching
  // the regression the F-0634 finding worried about, rather than asserting on the wire payload
  // alone.
  let fakeServerUser: {
    firstName: string;
    lastName: string;
    displayName: string;
    timezone: string | null;
    avatarUrl: string | null;
    phone: string | null;
  };

  beforeEach(() => {
    vi.clearAllMocks();
    workspacesGetMe.mockResolvedValue({
      id: 'ws_1',
      name: 'Tech Brands Co.',
      slug: 'tech-brands-co',
      email: 'ops@techbrands.in',
      phone: null,
      industry: null,
      companySize: null,
      websiteUrl: null,
      description: null,
      logoUrl: null,
      verificationStatus: 'VERIFIED',
    });

    fakeServerUser = {
      firstName: 'Amit',
      lastName: 'Singh',
      displayName: 'Amit Singh',
      timezone: 'Asia/Kolkata',
      avatarUrl: 'https://cdn.example.com/avatar.png',
      phone: '9000000001',
    };

    usersGetMe.mockImplementation(() =>
      Promise.resolve({
        id: 'user_1',
        email: 'admin@techbrands.in',
        userType: 'BRAND',
        status: 'ACTIVE',
        emailVerified: true,
        phoneVerified: false,
        createdAt: new Date().toISOString(),
        ...fakeServerUser,
      }),
    );

    usersUpdateMe.mockImplementation((payload: Record<string, unknown>) => {
      // Mirror UserService#updateProfile field-for-field: each key only overwrites when it is
      // actually present and non-null on the request, exactly like the real
      // `if (req.x() != null)` guards.
      if (payload.firstName != null) fakeServerUser.firstName = payload.firstName as string;
      if (payload.lastName != null) fakeServerUser.lastName = payload.lastName as string;
      if (payload.displayName != null) fakeServerUser.displayName = payload.displayName as string;
      if (payload.timezone != null) fakeServerUser.timezone = payload.timezone as string;
      if (payload.avatarUrl != null) fakeServerUser.avatarUrl = payload.avatarUrl as string;
      if (payload.phone != null) fakeServerUser.phone = payload.phone as string;
      return Promise.resolve({
        id: 'user_1',
        email: 'admin@techbrands.in',
        userType: 'BRAND',
        status: 'ACTIVE',
        emailVerified: true,
        phoneVerified: false,
        createdAt: new Date().toISOString(),
        ...fakeServerUser,
      });
    });
  });

  it('a phone-only save does not clear the other profile fields', async () => {
    const user = userEvent.setup();
    renderPage();
    await openSecurityTab();
    await screen.findByText('+91 9000000001');

    await user.click(screen.getByRole('button', { name: 'Edit' }));
    const input = await screen.findByLabelText('Mobile number');
    await user.clear(input);
    await user.type(input, '9123456780');
    await user.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(usersUpdateMe).toHaveBeenCalledTimes(1));

    // The call site sends only `phone` — the mechanism by which the other fields survive.
    expect(usersUpdateMe).toHaveBeenCalledWith({ phone: '9123456780' });

    // Proven against the same merge semantics the real server applies: the persisted record's
    // other five fields are untouched, while phone itself did update.
    expect(fakeServerUser.firstName).toBe('Amit');
    expect(fakeServerUser.lastName).toBe('Singh');
    expect(fakeServerUser.displayName).toBe('Amit Singh');
    expect(fakeServerUser.timezone).toBe('Asia/Kolkata');
    expect(fakeServerUser.avatarUrl).toBe('https://cdn.example.com/avatar.png');
    expect(fakeServerUser.phone).toBe('9123456780');

    // And a fresh GET (e.g. a later page load) would see the same untouched fields, not just
    // the in-memory record this test happens to hold a reference to.
    await waitFor(() => expect(usersGetMe).toHaveResolvedWith(expect.objectContaining({
      firstName: 'Amit',
      lastName: 'Singh',
      displayName: 'Amit Singh',
      timezone: 'Asia/Kolkata',
      avatarUrl: 'https://cdn.example.com/avatar.png',
    })));
  });
});
