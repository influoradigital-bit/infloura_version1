/**
 * ConnectedAccounts — "Granted permissions" reflects real Meta grants, not the requested set
 * (CR-104).
 *
 * WHY THIS FILE EXISTS
 * ---------------------
 * Before this fix, `CreatorMetaOAuthService.connect` (influora-api) stored and returned
 * `MetaOAuthService.REQUIRED_SCOPES` unconditionally as `grantedScopes` — the constant it
 * *requested*, never what Meta's `/me/permissions` actually granted. A creator who declined a
 * permission in the consent dialog still saw it listed as granted here. This file pins the
 * frontend half of the fix: the "Granted permissions" list renders exactly `connectionState
 * .scopes` (now sourced from real backend data via `useMetaConnection`/`GET /meta/oauth/status`),
 * and a `null` scopes value (permissions check failed server-side — "unknown", not a fabricated
 * full grant) renders its own honest message instead of silently disappearing or claiming a
 * grant that was never verified.
 *
 * Run: npx vitest run src/components/creator/connected-accounts.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ConnectedAccounts } from './connected-accounts';
import { useMetaConnection } from '@/hooks/creator/useMetaConnection';
import type { UseMetaConnectionResult } from '@/hooks/creator/useMetaConnection';

const toastMock = vi.fn();
vi.mock('@/hooks/use-toast', () => ({
  useToast: () => ({ toast: toastMock }),
}));

const metaDisconnect = vi.fn();
vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    api: {
      ...actual.api,
      metaOAuth: { ...actual.api.metaOAuth, disconnect: (...a: unknown[]) => metaDisconnect(...a) },
    },
  };
});

vi.mock('@/hooks/creator/useMetaConnection', () => ({
  useMetaConnection: vi.fn(),
}));

const mockedHook = vi.mocked(useMetaConnection);

function baseResult(overrides: Partial<UseMetaConnectionResult['data']> = {}): UseMetaConnectionResult {
  return {
    data: { connected: true, scopes: [], accountType: 'business', ...overrides },
    loading: false,
    error: null,
    refresh: vi.fn(),
  };
}

describe('ConnectedAccounts — Granted permissions (CR-104)', () => {
  it('renders only the scopes the backend reports as actually granted, not a fixed requested set', () => {
    mockedHook.mockReturnValue(baseResult({ scopes: ['instagram_basic', 'pages_show_list'] }));

    render(<ConnectedAccounts />);

    expect(screen.getByText(/Instagram profile & media/)).toBeInTheDocument();
    expect(screen.getByText(/Facebook Pages list/)).toBeInTheDocument();
    // The declined scope must not appear.
    expect(screen.queryByText(/Instagram insights & demographics/)).not.toBeInTheDocument();
  });

  it('a declined permission never shows up as granted even though it was requested server-side', () => {
    // Only instagram_basic granted — instagram_manage_insights and pages_show_list were declined.
    mockedHook.mockReturnValue(baseResult({ scopes: ['instagram_basic'] }));

    render(<ConnectedAccounts />);

    expect(screen.getByText(/Instagram profile & media/)).toBeInTheDocument();
    expect(screen.queryByText(/Instagram insights & demographics/)).not.toBeInTheDocument();
    expect(screen.queryByText(/Facebook Pages list/)).not.toBeInTheDocument();
  });

  it('scopes === null (backend permissions check failed) renders an honest "could not verify" message, not a full-grant list', () => {
    mockedHook.mockReturnValue(baseResult({ scopes: null }));

    render(<ConnectedAccounts />);

    expect(screen.getByText(/Couldn't verify which permissions were granted/)).toBeInTheDocument();
    // None of the scope labels should be fabricated onto the screen.
    expect(screen.queryByText(/Instagram profile & media/)).not.toBeInTheDocument();
    expect(screen.queryByText(/Instagram insights & demographics/)).not.toBeInTheDocument();
    expect(screen.queryByText(/Facebook Pages list/)).not.toBeInTheDocument();
  });

  it('not connected: no permissions section renders at all', () => {
    mockedHook.mockReturnValue(baseResult({ connected: false, scopes: [] }));

    render(<ConnectedAccounts />);

    expect(screen.queryByText('Granted permissions')).not.toBeInTheDocument();
    expect(screen.queryByText(/Couldn't verify which permissions/)).not.toBeInTheDocument();
  });
});

describe('ConnectedAccounts — disconnect (CR-102 / F-0115)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    metaDisconnect.mockResolvedValue({ disconnected: true });
  });

  it('no Disconnect control exists when not connected', () => {
    mockedHook.mockReturnValue(baseResult({ connected: false, scopes: [] }));
    render(<ConnectedAccounts />);

    expect(screen.queryByText(/disconnect instagram/i)).not.toBeInTheDocument();
  });

  it('shows a confirmation dialog before disconnecting — a click alone does not call the API', async () => {
    mockedHook.mockReturnValue(baseResult({ scopes: ['instagram_basic'] }));
    const user = userEvent.setup();
    render(<ConnectedAccounts />);

    await user.click(screen.getByRole('button', { name: /disconnect instagram/i }));

    expect(await screen.findByText('Disconnect Instagram & Facebook?')).toBeInTheDocument();
    expect(metaDisconnect).not.toHaveBeenCalled();
  });

  // Priya review (2 rounds): both prior wordings claimed a user-visible consequence no code
  // path actually produces ("deliverable verification stops"; "brands will no longer see your
  // reach/engagement" — disconnect only revokes the token, it never touches the persisted
  // CreatorProfile/PlatformStat rows brands actually read). This pins the corrected, verified
  // claim so a regression back to a fabricated consequence fails red, not just a vibe check.
  it('the confirmation copy makes only the claim the code actually backs — syncing stops, not brand visibility', async () => {
    mockedHook.mockReturnValue(baseResult({ scopes: ['instagram_basic'] }));
    const user = userEvent.setup();
    render(<ConnectedAccounts />);

    await user.click(screen.getByRole('button', { name: /disconnect instagram/i }));

    expect(
      await screen.findByText(
        "Your Instagram metrics will stop syncing, so the reach and engagement brands see will stay frozen at today's numbers until you reconnect.",
      ),
    ).toBeInTheDocument();
    expect(screen.queryByText(/brands will no longer see/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/deliverable verification/i)).not.toBeInTheDocument();
  });

  it('calls api.metaOAuth.disconnect and refreshes status on confirm', async () => {
    const refresh = vi.fn().mockResolvedValue(undefined);
    mockedHook.mockReturnValue({ ...baseResult({ scopes: ['instagram_basic'] }), refresh });
    const user = userEvent.setup();
    render(<ConnectedAccounts />);

    await user.click(screen.getByRole('button', { name: /disconnect instagram/i }));
    await user.click(await screen.findByRole('button', { name: 'Disconnect' }));

    await waitFor(() => expect(metaDisconnect).toHaveBeenCalled());
    await waitFor(() => expect(refresh).toHaveBeenCalled());
    expect(toastMock).toHaveBeenCalledWith(
      expect.objectContaining({ title: 'Instagram and Facebook disconnected' }),
    );
  });

  it('a failed disconnect shows an error toast and keeps the dialog open for retry', async () => {
    metaDisconnect.mockRejectedValue(new Error('network down'));
    mockedHook.mockReturnValue(baseResult({ scopes: ['instagram_basic'] }));
    const user = userEvent.setup();
    render(<ConnectedAccounts />);

    await user.click(screen.getByRole('button', { name: /disconnect instagram/i }));
    await user.click(await screen.findByRole('button', { name: 'Disconnect' }));

    await waitFor(() =>
      expect(toastMock).toHaveBeenCalledWith(
        expect.objectContaining({ title: 'Could not disconnect', variant: 'destructive' }),
      ),
    );
    // Still open — a failed disconnect must not strand the creator with no way to retry.
    expect(screen.getByText('Disconnect Instagram & Facebook?')).toBeInTheDocument();
  });
});
