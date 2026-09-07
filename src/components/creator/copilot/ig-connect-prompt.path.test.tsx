/**
 * IGConnectPrompt — the Co-pilot nudge must ask the Facebook-Page question too (T-IGTRUST-0907).
 *
 * WHY THIS FILE EXISTS
 * ---------------------
 * `api.metaOAuth.authorize()` with no argument defaults to FACEBOOK_LOGIN, the configuration
 * that requires an Instagram professional account already linked to a Facebook Page the creator
 * can administer. T-IGLOGIN-0820 added the pre-redirect question to the Settings card only; this
 * prompt and creator onboarding kept calling `authorize()` bare, so every creator reaching Meta
 * from here was sent down the Page-required path and a creator without a Page dead-ended inside
 * Meta's own UI with nothing explaining why.
 *
 * The `toHaveBeenCalledWith` assertions below fail against the pre-fix tree, where the call
 * carried no argument at all.
 *
 * Run: npx vitest run src/components/creator/copilot/ig-connect-prompt.path.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { IGConnectPrompt } from './IGConnectPrompt';

const toastMock = vi.fn();
vi.mock('@/hooks/use-toast', () => ({
  useToast: () => ({ toast: toastMock }),
}));

const authorizeMock = vi.fn();
const setConnectReturnToMock = vi.fn();

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    api: {
      ...actual.api,
      metaOAuth: {
        ...actual.api.metaOAuth,
        authorize: (...a: unknown[]) => authorizeMock(...a),
        setConnectReturnTo: (...a: unknown[]) => setConnectReturnToMock(...a),
      },
    },
  };
});

beforeEach(() => {
  vi.clearAllMocks();
  authorizeMock.mockResolvedValue({ authorizationUrl: 'https://meta.example/dialog', state: 's' });
  Object.defineProperty(window, 'location', {
    configurable: true,
    value: { ...window.location, assign: vi.fn(), href: 'http://localhost/' },
  });
});

describe('IGConnectPrompt — pre-redirect path choice', () => {
  it('asks before redirecting instead of starting OAuth on the first click', async () => {
    const user = userEvent.setup();
    render(<IGConnectPrompt />);

    await user.click(screen.getByRole('button', { name: /Connect Instagram/i }));

    expect(
      await screen.findByText(/Is your Instagram linked to a Facebook Page\?/i),
    ).toBeInTheDocument();
    expect(authorizeMock).not.toHaveBeenCalled();
  });

  it('honours "no Page" with INSTAGRAM_LOGIN rather than the FACEBOOK_LOGIN default', async () => {
    const user = userEvent.setup();
    render(<IGConnectPrompt />);

    await user.click(screen.getByRole('button', { name: /Connect Instagram/i }));
    await user.click(await screen.findByText(/No — Instagram only/i));

    await waitFor(() => expect(authorizeMock).toHaveBeenCalledTimes(1));
    expect(authorizeMock).toHaveBeenCalledWith('INSTAGRAM_LOGIN');
    expect(authorizeMock).not.toHaveBeenCalledWith(undefined);
  });

  it('still records the Co-pilot return path so the callback does not divert to Settings (CR-65)', async () => {
    const user = userEvent.setup();
    render(<IGConnectPrompt />);

    await user.click(screen.getByRole('button', { name: /Connect Instagram/i }));
    await user.click(await screen.findByText(/Yes — I have a Facebook Page/i));

    await waitFor(() => expect(authorizeMock).toHaveBeenCalledWith('FACEBOOK_LOGIN'));
    expect(setConnectReturnToMock).toHaveBeenCalledWith('/creator/copilot');
  });
});
