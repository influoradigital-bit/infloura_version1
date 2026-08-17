/**
 * Brand Settings — F-0249 (stale-seed-overwrites-real-data).
 *
 * WHY THIS FILE EXISTS
 * ---------------------
 * `settings` (workspaceName/email/phone/website) used to always initialize from a hardcoded
 * mock seed ("Tech Brands Co." / "admin@techbrands.in"), even in live mode. If
 * `GET /workspaces/me` failed, that seed stayed sitting in the form and the Save button — only
 * gated on `workspaceInfoLoading || workspaceInfoSaving`, both false once the failed request
 * settles — stayed enabled. One click PATCHed the fabricated seed over the brand's real
 * workspace name and billing email. The fix: in live mode the fields start empty (never the
 * mock seed), and Save stays disabled until an authoritative GET /workspaces/me response has
 * been received (see `workspaceInfoLoaded` in src/pages/brand-settings.tsx).
 *
 * HARNESS NOTES — mirrors creator-settings-change-password.test.tsx's `vi.mock('@/lib/api', ...)`
 * shape (isApiLive as a controllable mock fn, api.* as fine-grained vi.fn()s).
 *
 * Run: npx vitest run src/pages/__tests__/brand-settings.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import BrandSettingsPage from '../brand-settings';

const navigateMock = vi.fn();
vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom');
  return { ...actual, useNavigate: () => navigateMock };
});

vi.mock('@/lib/store', () => ({
  useAuthStore: () => ({ logout: vi.fn() }),
}));

const toastMock = vi.fn();
vi.mock('@/hooks/use-toast', () => ({
  useToast: () => ({ toast: (...a: unknown[]) => toastMock(...a) }),
  toast: (...a: unknown[]) => toastMock(...a),
}));

const apiLive = vi.fn();
const getMe = vi.fn();
const updateMe = vi.fn();

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    isApiLive: () => apiLive(),
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
    },
  };
});

const MOCK_SEED_NAME = 'Tech Brands Co.';
const MOCK_SEED_EMAIL = 'admin@techbrands.in';

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/brand/settings']}>
      <BrandSettingsPage />
    </MemoryRouter>,
  );
}

describe('BrandSettingsPage — Workspace Information (F-0249)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('live mode, GET /workspaces/me REJECTS: never seeds the mock values and disables Save', async () => {
    apiLive.mockReturnValue(true);
    getMe.mockRejectedValue(new Error('network down'));

    renderPage();

    // Load failure must surface, not hang silently.
    await screen.findByText('Could not load workspace information.');

    // (a) the mock seed must never appear anywhere in the rendered output.
    expect(screen.queryByDisplayValue(MOCK_SEED_NAME)).not.toBeInTheDocument();
    expect(screen.queryByDisplayValue(MOCK_SEED_EMAIL)).not.toBeInTheDocument();
    expect(screen.queryByText(MOCK_SEED_NAME)).not.toBeInTheDocument();
    expect(screen.queryByText(MOCK_SEED_EMAIL)).not.toBeInTheDocument();

    // The workspace name field must be empty, not pre-filled with anything submittable.
    expect(screen.getByLabelText('Workspace Name')).toHaveValue('');

    // (b) Save must be disabled — a load failure must never leave a write control enabled
    // over data the client never received.
    expect(screen.getByRole('button', { name: /Save Changes/i })).toBeDisabled();

    // A retry affordance must be present.
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument();

    // Clicking Save while disabled must not be capable of reaching the API even if some
    // future change to the DOM/button library lets a disabled click fire.
    expect(updateMe).not.toHaveBeenCalled();
  });

  it('live mode, GET /workspaces/me succeeds: hydrates real values and enables Save', async () => {
    apiLive.mockReturnValue(true);
    getMe.mockResolvedValue({
      id: 'ws_real',
      name: 'Real Brand Pvt Ltd',
      slug: 'real-brand',
      email: 'ops@realbrand.com',
      phone: '+91 90000 00001',
      industry: null,
      companySize: null,
      websiteUrl: 'www.realbrand.com',
      logoUrl: null,
      verificationStatus: 'VERIFIED',
    });
    updateMe.mockResolvedValue({
      id: 'ws_real',
      name: 'Real Brand Pvt Ltd',
      slug: 'real-brand',
      email: 'ops@realbrand.com',
      phone: '+91 90000 00001',
      industry: null,
      companySize: null,
      websiteUrl: 'www.realbrand.com',
      logoUrl: null,
      verificationStatus: 'VERIFIED',
    });

    renderPage();

    await waitFor(() => expect(screen.getByLabelText('Workspace Name')).toHaveValue('Real Brand Pvt Ltd'));
    expect(screen.getByLabelText('Email')).toHaveValue('ops@realbrand.com');

    // No trace of the mock seed anywhere once real data has loaded.
    expect(screen.queryByDisplayValue(MOCK_SEED_NAME)).not.toBeInTheDocument();
    expect(screen.queryByDisplayValue(MOCK_SEED_EMAIL)).not.toBeInTheDocument();

    const saveButton = screen.getByRole('button', { name: /Save Changes/i });
    await waitFor(() => expect(saveButton).not.toBeDisabled());

    fireEvent.click(saveButton);

    await waitFor(() =>
      expect(updateMe).toHaveBeenCalledWith({
        name: 'Real Brand Pvt Ltd',
        email: 'ops@realbrand.com',
        phone: '+91 90000 00001',
        websiteUrl: 'www.realbrand.com',
      }),
    );
    await waitFor(() =>
      expect(toastMock).toHaveBeenCalledWith(expect.objectContaining({ title: 'Workspace updated' })),
    );
  });
});
