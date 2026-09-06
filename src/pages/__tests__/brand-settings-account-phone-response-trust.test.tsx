/**
 * Brand Settings — Account Mobile Number: after PATCH /users/me, the UI repaints from the
 * response body rather than re-fetching (F-0635 investigation, ruled correct-as-is; no fix).
 *
 * WHY THIS FILE EXISTS
 * ---------------------
 * F-0635 read as "handleSavePhone calls `setSavedPhone(updated.phone)` off the PATCH response
 * instead of re-running GET /users/me, so the UI might show a stale/wrong value." Ruled
 * correct-as-is: `updated` IS the server's authoritative UserProfileMeResponse for this exact
 * write, returned over the same request/response pair — a forced re-GET adds a round trip and a
 * new failure mode (what does a GET failing right after a successful PATCH even mean to the
 * user?) for zero added correctness. It is, in fact, MORE correct than echoing the locally-typed
 * value: if the server ever canonicalizes/reformats the phone on write, trusting the response is
 * the only way the UI shows the true stored value.
 *
 * This file proves both halves directly at the handleSavePhone call site:
 *  1. When the PATCH response's `phone` differs in formatting from what was typed/sent, the UI
 *     displays the RESPONSE's value, not an echo of local state. Asserting only the outgoing
 *     request body (as the sibling account-phone test file already does) cannot tell these
 *     apart — both implementations would send the same normalized digits either way. Only
 *     checking what is rendered afterward distinguishes "trusts the response" from "echoes local
 *     state".
 *  2. GET /users/me is called exactly once (the initial mount load) — a successful save never
 *     triggers a second GET.
 *
 * FALSIFIED (2026-09-05):
 *  - Test 1: temporarily changed `setSavedPhone(updated.phone)` to `setSavedPhone(normalized)`
 *    (echo the locally-typed value instead of the response). The test failed because the card
 *    showed the plain typed digits instead of the server's differently-formatted string.
 *    Reverted.
 *  - Test 2: temporarily added a `loadAccountPhone()` call at the end of the try block in
 *    handleSavePhone (a forced re-fetch). The test failed because `usersGetMe` was called twice
 *    instead of once. Reverted.
 *
 * HARNESS NOTES — mirrors brand-settings-account-phone.test.tsx's `vi.mock('@/lib/api', ...)` shape.
 *
 * Run: npx vitest run src/pages/__tests__/brand-settings-account-phone-response-trust.test.tsx
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

function mockUserProfile(overrides: Partial<{ phone: string | null }> = {}) {
  return {
    id: 'user_1',
    email: 'admin@techbrands.in',
    displayName: 'Amit Singh',
    firstName: 'Amit',
    lastName: 'Singh',
    userType: 'BRAND',
    status: 'ACTIVE',
    avatarUrl: null,
    emailVerified: true,
    phoneVerified: false,
    phone: '9000000001',
    timezone: null,
    createdAt: new Date().toISOString(),
    ...overrides,
  };
}

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

describe('BrandSettingsPage — Account Mobile Number: save repaints from the PATCH response (F-0635)', () => {
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
  });

  it('after save, the card shows the phone exactly as the server returned it — not the locally-typed value', async () => {
    usersGetMe.mockResolvedValue(mockUserProfile({ phone: '9000000001' }));
    // The server's authoritative response differs in FORMATTING from what was typed/sent — it
    // canonicalizes with a space; the client sent plain digits.
    usersUpdateMe.mockResolvedValue(mockUserProfile({ phone: '98765 43210' }));
    const user = userEvent.setup();
    renderPage();
    await openSecurityTab();
    await screen.findByText('+91 9000000001');

    await user.click(screen.getByRole('button', { name: 'Edit' }));
    const input = await screen.findByLabelText('Mobile number');
    await user.clear(input);
    await user.type(input, '9876543210');
    await user.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(usersUpdateMe).toHaveBeenCalledWith({ phone: '9876543210' }));

    // The card must show the server's own formatted string...
    expect(await screen.findByText('+91 98765 43210')).toBeInTheDocument();
    // ...never the raw digits the client normalized and sent — that would mean the UI echoed
    // local state instead of trusting the response.
    expect(screen.queryByText('+91 9876543210')).not.toBeInTheDocument();
  });

  it('a successful save does not re-fetch GET /users/me', async () => {
    usersGetMe.mockResolvedValue(mockUserProfile({ phone: '9000000001' }));
    usersUpdateMe.mockResolvedValue(mockUserProfile({ phone: '9123456780' }));
    const user = userEvent.setup();
    renderPage();
    await openSecurityTab();
    await screen.findByText('+91 9000000001');

    await waitFor(() => expect(usersGetMe).toHaveBeenCalledTimes(1));

    await user.click(screen.getByRole('button', { name: 'Edit' }));
    const input = await screen.findByLabelText('Mobile number');
    await user.clear(input);
    await user.type(input, '9123456780');
    await user.click(screen.getByRole('button', { name: 'Save' }));

    await screen.findByText('+91 9123456780');

    // The save round trip is the PATCH alone — no forced re-GET after a successful write.
    expect(usersGetMe).toHaveBeenCalledTimes(1);
  });
});
