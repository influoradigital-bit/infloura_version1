/**
 * Brand Settings — Account Mobile Number (PHONE-0904 Q1 sign-off item).
 *
 * WHY THIS FILE EXISTS
 * ---------------------
 * Sign-off Q1 found brand phone was write-once/read-never: a brand that mistyped its number at
 * signup could never see or correct it. Vikram's backend landed GET/PATCH /users/me
 * (UserController -> UserService, UserDtos.UserProfileDto/UpdateProfileRequest both now carry
 * `phone`). This is the frontend half — a new "Mobile Number" row in brand-settings.tsx's
 * Security tab, deliberately SEPARATE from the existing "Phone" field in the Workspace
 * Information card (that one is `workspaces.phone`, optional, blank-clears, a different column
 * with different validation — see the comments in brand-settings.tsx).
 *
 * Because phone is now REQUIRED for a brand (Q8), there is no valid "cleared" state: a blank
 * PATCH gets PHONE_REQUIRED. The UI must never offer a control that can produce that error —
 * this file proves the Save button is disabled on an empty draft, not just that the server
 * error is mapped.
 *
 * HARNESS NOTES — mirrors brand-settings.test.tsx's `vi.mock('@/lib/api', ...)` shape. Runs in
 * mock mode (isApiLive() => false is NOT set here; api.users.getMe/updateMe are mocked directly,
 * same as api.workspaces.getMe/updateMe in the sibling F-0249 file) so the effect always calls
 * through the mocked api.users functions regardless of live/mock branching inside api.ts itself.
 *
 * Run: npx vitest run src/pages/__tests__/brand-settings-account-phone.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import BrandSettingsPage from '../brand-settings';
import { ApiError } from '@/lib/api';

const navigateMock = vi.fn();
vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom');
  return { ...actual, useNavigate: () => navigateMock };
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

describe('BrandSettingsPage — Account Mobile Number (PHONE-0904 Q1)', () => {
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

  it('renders the saved number from GET /users/me', async () => {
    usersGetMe.mockResolvedValue(mockUserProfile({ phone: '9000000001' }));
    renderPage();
    await openSecurityTab();

    await waitFor(() => expect(usersGetMe).toHaveBeenCalledTimes(1));
    expect(await screen.findByText('+91 9000000001')).toBeInTheDocument();
  });

  it('a valid edit sends the normalized value to PATCH /users/me', async () => {
    usersGetMe.mockResolvedValue(mockUserProfile({ phone: '9000000001' }));
    usersUpdateMe.mockResolvedValue(mockUserProfile({ phone: '9123456780' }));
    const user = userEvent.setup();
    renderPage();
    await openSecurityTab();
    await screen.findByText('+91 9000000001');

    await user.click(screen.getByRole('button', { name: 'Edit' }));
    const input = await screen.findByLabelText('Mobile number');
    await user.clear(input);
    await user.type(input, '9123456780');
    await user.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(usersUpdateMe).toHaveBeenCalledWith({ phone: '9123456780' }));
  });

  it('a pasted "+91 98765 43210" is accepted, not rejected client-side', async () => {
    usersGetMe.mockResolvedValue(mockUserProfile({ phone: '9000000001' }));
    usersUpdateMe.mockResolvedValue(mockUserProfile({ phone: '9876543210' }));
    const user = userEvent.setup();
    renderPage();
    await openSecurityTab();
    await screen.findByText('+91 9000000001');

    await user.click(screen.getByRole('button', { name: 'Edit' }));
    const input = await screen.findByLabelText('Mobile number');
    await user.clear(input);
    await user.click(input);
    await user.paste('+91 98765 43210');

    // Save must be enabled (client-side normalization accepted the pasted value) and must send
    // the normalized 10-digit form, not the raw paste with its country code / spaces.
    const saveButton = screen.getByRole('button', { name: 'Save' });
    expect(saveButton).not.toBeDisabled();
    await user.click(saveButton);

    await waitFor(() => expect(usersUpdateMe).toHaveBeenCalledWith({ phone: '9876543210' }));
  });

  it('the field cannot be cleared and saved', async () => {
    usersGetMe.mockResolvedValue(mockUserProfile({ phone: '9000000001' }));
    const user = userEvent.setup();
    renderPage();
    await openSecurityTab();
    await screen.findByText('+91 9000000001');

    await user.click(screen.getByRole('button', { name: 'Edit' }));
    const input = await screen.findByLabelText('Mobile number');
    await user.clear(input);

    // Save is disabled client-side on an empty draft — never reaches the API to learn
    // PHONE_REQUIRED the hard way.
    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled();
    expect(usersUpdateMe).not.toHaveBeenCalled();

    // Helper text explains why, so the dead end isn't silent.
    expect(screen.getByText(/can't be saved empty/i)).toBeInTheDocument();
  });

  it('PHONE_ALREADY_EXISTS renders its own distinct inline message on the field', async () => {
    usersGetMe.mockResolvedValue(mockUserProfile({ phone: '9000000001' }));
    usersUpdateMe.mockRejectedValue(
      new ApiError('PHONE_ALREADY_EXISTS', 'A user with this phone number already exists', 409),
    );
    const user = userEvent.setup();
    renderPage();
    await openSecurityTab();
    await screen.findByText('+91 9000000001');

    await user.click(screen.getByRole('button', { name: 'Edit' }));
    const input = await screen.findByLabelText('Mobile number');
    await user.clear(input);
    await user.type(input, '9123456780');
    await user.click(screen.getByRole('button', { name: 'Save' }));

    expect(await screen.findByText('This mobile number is already registered')).toBeInTheDocument();
  });

  it('INVALID_PHONE renders its own distinct inline message on the field', async () => {
    usersGetMe.mockResolvedValue(mockUserProfile({ phone: '9000000001' }));
    usersUpdateMe.mockRejectedValue(new ApiError('INVALID_PHONE', 'Phone number is invalid', 400));
    const user = userEvent.setup();
    renderPage();
    await openSecurityTab();
    await screen.findByText('+91 9000000001');

    await user.click(screen.getByRole('button', { name: 'Edit' }));
    const input = await screen.findByLabelText('Mobile number');
    await user.clear(input);
    await user.type(input, '9123456780');
    await user.click(screen.getByRole('button', { name: 'Save' }));

    expect(await screen.findByText('Enter a valid 10-digit mobile number')).toBeInTheDocument();
  });

  it('PHONE_REQUIRED renders its own distinct inline message on the field', async () => {
    // Defense-in-depth path: the client-side empty guard already blocks Save on a blank draft
    // (see the dedicated test above), but the server error must still be mapped rather than
    // falling through to a generic toast if it is ever reached another way.
    usersGetMe.mockResolvedValue(mockUserProfile({ phone: '9000000001' }));
    usersUpdateMe.mockRejectedValue(new ApiError('PHONE_REQUIRED', 'Phone number is required', 400));
    const user = userEvent.setup();
    renderPage();
    await openSecurityTab();
    await screen.findByText('+91 9000000001');

    await user.click(screen.getByRole('button', { name: 'Edit' }));
    const input = await screen.findByLabelText('Mobile number');
    await user.clear(input);
    await user.type(input, '9123456780');
    await user.click(screen.getByRole('button', { name: 'Save' }));

    expect(
      await screen.findByText('Mobile number is required and cannot be removed.'),
    ).toBeInTheDocument();
  });
});
