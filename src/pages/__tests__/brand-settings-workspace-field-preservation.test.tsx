/**
 * Brand Settings — Workspace Information save must not wipe fields the form doesn't touch (F-0462).
 *
 * WHY THIS FILE EXISTS
 * ---------------------
 * PATCH /workspaces/me is a genuine full-replace on the server: `Workspace#applyCompanyDetails`
 * (influora-api/src/main/java/com/influora/domain/entity/Workspace.java) unconditionally assigns
 * `this.industry = industry`, `this.companySize = companySize`, etc. with no `!= null` guard — an
 * omitted field in the request body is cleared, not left alone (confirmed by reading
 * WorkspaceService#updateMyWorkspace and Workspace#applyCompanyDetails directly, not assumed).
 *
 * The "Workspace Information" card only surfaces four fields — name/email/phone/website — and
 * never surfaces industry/companySize/description/logoUrl. A save handler that sent only those
 * four would silently null out the other four on every single save. `handleSaveWorkspaceInfo` in
 * brand-settings.tsx guards against this by carrying `industry`/`companySize`/`description`/
 * `logoUrl` forward from the last-loaded `WorkspaceMeResponse` (`loadedWorkspace`) on every PATCH.
 * This file proves that merge actually happens — a regression that dropped it would still pass
 * `brand-settings.test.tsx` (F-0249), because `toHaveBeenCalledWith`'s object equality treats an
 * `undefined`-valued key as equivalent to an absent one, so it can't tell "sent as undefined" apart
 * from "never sent". Asserting on the literal object catches an actual value being dropped, which
 * matters once a field like `industry` is non-null.
 *
 * HARNESS NOTES — mirrors brand-settings.test.tsx's `vi.mock('@/lib/api', ...)` shape.
 *
 * Run: npx vitest run src/pages/__tests__/brand-settings-workspace-field-preservation.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
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

const getMe = vi.fn();
const updateMe = vi.fn();
const usersGetMe = vi.fn();

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    isApiLive: () => true,
    api: {
      workspaces: {
        getMe: (...a: unknown[]) => getMe(...a),
        updateMe: (...a: unknown[]) => updateMe(...a),
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
        updateMe: vi.fn().mockResolvedValue({}),
      },
    },
  };
});

// The workspace record as loaded from GET /workspaces/me — carries real, non-null values for the
// four fields the form never surfaces. If the save handler regresses to sending only
// name/email/phone/websiteUrl, this record's industry/companySize/description/logoUrl are what a
// full-replace PATCH would silently clear.
const LOADED_WORKSPACE = {
  id: 'ws_real',
  name: 'Real Brand Pvt Ltd',
  slug: 'real-brand',
  email: 'ops@realbrand.com',
  phone: '+91 90000 00001',
  industry: 'D2C Fashion',
  companySize: 'SMB',
  websiteUrl: 'www.realbrand.com',
  description: 'A real workspace bio set at onboarding.',
  logoUrl: 'https://cdn.example.com/logo.png',
  verificationStatus: 'VERIFIED',
};

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/brand/settings']}>
      <BrandSettingsPage />
    </MemoryRouter>,
  );
}

describe('BrandSettingsPage — Workspace Information save preserves untouched fields (F-0462)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    usersGetMe.mockResolvedValue({
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
    });
    getMe.mockResolvedValue(LOADED_WORKSPACE);
    updateMe.mockResolvedValue(LOADED_WORKSPACE);
  });

  it('a save that only edits Workspace Name still sends the loaded industry/companySize/description/logoUrl', async () => {
    renderPage();

    await waitFor(() => expect(screen.getByLabelText('Workspace Name')).toHaveValue('Real Brand Pvt Ltd'));

    // The user touches only the name field — never industry, companySize, description, or
    // logoUrl, none of which this form even renders an input for.
    fireEvent.change(screen.getByLabelText('Workspace Name'), { target: { value: 'Real Brand Pvt Ltd (Renamed)' } });

    const saveButton = screen.getByRole('button', { name: /Save Changes/i });
    await waitFor(() => expect(saveButton).not.toBeDisabled());
    fireEvent.click(saveButton);

    await waitFor(() => expect(updateMe).toHaveBeenCalledTimes(1));

    // The PATCH body must carry forward the four fields this form never surfaces, at their real
    // loaded values — a full-replace PATCH that omitted them would clear industry/companySize/
    // description/logoUrl on the server the instant this save lands.
    const body = updateMe.mock.calls[0][0];
    expect(body.industry).toBe('D2C Fashion');
    expect(body.companySize).toBe('SMB');
    expect(body.description).toBe('A real workspace bio set at onboarding.');
    expect(body.logoUrl).toBe('https://cdn.example.com/logo.png');
    // And the field the user actually edited went through.
    expect(body.name).toBe('Real Brand Pvt Ltd (Renamed)');
  });
});
