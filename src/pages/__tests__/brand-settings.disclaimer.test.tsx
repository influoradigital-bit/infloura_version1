/**
 * Brand Settings — F-0262 (stale-scope-disclaimer).
 *
 * WHY THIS FILE EXISTS
 * ---------------------
 * The Notifications tab used to render one blanket disclaimer — "Settings sync isn't available
 * yet — changes apply to this session only" — under a disabled "Save Preferences" button, as if
 * it described the whole card. It did not: Email Notifications (handleEmailPrefChange),
 * Campaign Alerts, and Bid Notifications (handleCategoryPrefChange) each call
 * api.notifications.setPreference the moment their switch is flipped, in live mode. Only Push
 * Notifications and Weekly Digest are genuinely session-only (no backend to hit; both switches
 * are `disabled`).
 *
 * This file proves: (a) the three live switches still persist to the server when live mode is
 * on, (b) no rendered copy claims those three are ephemeral, and (c) the two genuinely-unwired
 * controls still disclose that individually.
 *
 * HARNESS NOTE — the Switch component (Radix) has no accessible name wired to the adjacent
 * label text (no aria-labelledby, no wrapping <label>), so switches are located by walking up
 * from their label text to the shared row container, not by role name.
 *
 * Run: npx vitest run src/pages/__tests__/brand-settings.disclaimer.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within, fireEvent } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
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

vi.mock('@/hooks/use-toast', () => ({
  useToast: () => ({ toast: vi.fn() }),
  toast: vi.fn(),
}));

const getMe = vi.fn();
const updateMe = vi.fn();
const getPreferences = vi.fn();
const setPreference = vi.fn();

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
        getPreferences: (...a: unknown[]) => getPreferences(...a),
        setPreference: (...a: unknown[]) => setPreference(...a),
      },
      auth: {
        logout: vi.fn().mockResolvedValue({ message: 'ok' }),
        changePassword: vi.fn().mockResolvedValue({ changed: true }),
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

// Radix TabsTrigger selects on `onMouseDown`, not `onClick` (see
// node_modules/@radix-ui/react-tabs) — userEvent.click fires the full pointer/mouse sequence a
// real click produces, unlike a bare fireEvent.click which only dispatches `click`.
async function openNotificationsTab() {
  const user = userEvent.setup();
  await user.click(screen.getByRole('tab', { name: /Notifications/i }));
}

/** Walk up from a control's label text to its shared row container, then find the switch inside it. */
function getSwitchByLabel(label: string | RegExp): HTMLElement {
  const labelEl = screen.getByText(label);
  const row = labelEl.closest('.flex.items-center.justify-between');
  if (!row) throw new Error(`Could not find the row container for label: ${label}`);
  return within(row as HTMLElement).getByRole('switch');
}

describe('BrandSettingsPage — Notifications persistence copy (F-0262)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    getMe.mockResolvedValue({
      id: 'ws_1',
      name: 'Real Brand',
      slug: 'real-brand',
      email: 'ops@realbrand.com',
      phone: null,
      industry: null,
      companySize: null,
      websiteUrl: null,
      logoUrl: null,
      verificationStatus: 'VERIFIED',
    });
    getPreferences.mockResolvedValue([]);
    setPreference.mockResolvedValue({ ok: true });
  });

  it('does not claim the whole notifications card is session-only / non-persisting', async () => {
    renderPage();
    await openNotificationsTab();
    await waitFor(() => expect(getPreferences).toHaveBeenCalled());

    // The old blanket lie must be gone.
    expect(screen.queryByText(/changes apply to this session only/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/settings sync isn.t available yet/i)).not.toBeInTheDocument();
    // The old disabled batch-save button (which the blanket disclaimer hung off of) is gone too
    // — there is nothing left to batch-save once every persistable toggle saves on change.
    expect(screen.queryByRole('button', { name: /Save Preferences/i })).not.toBeInTheDocument();
  });

  it('flipping Email Notifications PATCHes the server immediately in live mode', async () => {
    renderPage();
    await openNotificationsTab();
    await waitFor(() => expect(getPreferences).toHaveBeenCalled());

    fireEvent.click(getSwitchByLabel('Email Notifications'));

    await waitFor(() => expect(setPreference).toHaveBeenCalledWith('brand', '*', false));
  });

  it('flipping Campaign Alerts PATCHes every eventType in its group immediately', async () => {
    renderPage();
    await openNotificationsTab();
    await waitFor(() => expect(getPreferences).toHaveBeenCalled());

    fireEvent.click(getSwitchByLabel('Campaign Alerts'));

    await waitFor(() => expect(setPreference).toHaveBeenCalledWith('brand', 'application.created', false));
    expect(setPreference).toHaveBeenCalledWith('brand', 'deliverable.submitted', false);
    expect(setPreference).toHaveBeenCalledWith('brand', 'shipment.received', false);
  });

  it('flipping Bid Notifications PATCHes every eventType in its group immediately', async () => {
    renderPage();
    await openNotificationsTab();
    await waitFor(() => expect(getPreferences).toHaveBeenCalled());

    fireEvent.click(getSwitchByLabel('Bid Notifications'));

    await waitFor(() => expect(setPreference).toHaveBeenCalledWith('brand', 'bid.countered', false));
    expect(setPreference).toHaveBeenCalledWith('brand', 'proposal.accepted', false);
  });

  it('Push Notifications and Weekly Digest still individually disclose they are unavailable', async () => {
    renderPage();
    await openNotificationsTab();
    await waitFor(() => expect(getPreferences).toHaveBeenCalled());

    const pushSwitch = getSwitchByLabel('Push Notifications');
    expect(pushSwitch).toBeDisabled();
    expect(pushSwitch).toHaveAttribute('title', 'Push notifications are not available yet');

    const digestSwitch = getSwitchByLabel('Weekly Digest');
    expect(digestSwitch).toBeDisabled();
    expect(digestSwitch).toHaveAttribute('title', "Weekly digest isn't available yet");
  });
});
