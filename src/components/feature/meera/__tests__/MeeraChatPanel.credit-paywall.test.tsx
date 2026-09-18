/**
 * F-0902 — Meera credit paywall dead end.
 *
 * When a live turn came back CREDITS_EXHAUSTED the paywall's "Fund a campaign" CTA called
 * `onFunctionCall('request_payment')` with no payment payload. That opened StageFunding with
 * nothing to fund, so the brand sat on a permanent "Securing your funds…" loader. The copy was
 * also misleading: the escrow-funded credit reset runs only inside Meera's confirm_launch tool,
 * which the model is never offered, so funding a campaign from the campaign UI never unlocked
 * Meera. A plan upgrade is what restores credits, so the CTA now links to billing.
 *
 * Run: npx vitest run src/components/feature/meera/__tests__/MeeraChatPanel.credit-paywall.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';

if (!('scrollTo' in Element.prototype)) {
  Object.defineProperty(Element.prototype, 'scrollTo', {
    value: () => {},
    writable: true,
    configurable: true,
  });
}

vi.mock('@/hooks/useMeeraStream', () => ({
  useMeeraStream: () => ({ status: 'idle', open: vi.fn(), close: vi.fn(), lastError: null }),
}));

vi.mock('@/hooks/useVoiceOutput', () => ({
  useVoiceOutput: () => ({
    supported: false,
    enabled: false,
    setEnabled: vi.fn(),
    isSpeaking: false,
    speak: vi.fn(),
    speakSequence: vi.fn(),
    stop: vi.fn(),
  }),
}));

// The paywall only exists on the LIVE path (vitest.config pins mock mode).
vi.mock('@/lib/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/api')>();
  return { ...actual, isApiLive: () => true };
});

vi.mock('@/lib/meera-api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/meera-api')>();
  return {
    ...actual,
    meeraApi: {
      ...actual.meeraApi,
      startSession: vi.fn(),
      getHistory: vi.fn(),
      sendTurn: vi.fn(),
      getMessagesAfter: vi.fn(),
      logOptionTapped: vi.fn(),
    },
  };
});

import { ApiError } from '@/lib/api';
import { meeraApi } from '@/lib/meera-api';
import { MEERA_PAYWALL } from '@/data/meera-copy';
import { MeeraChatPanel } from '../MeeraChatPanel';

function renderPanel() {
  const onFunctionCall = vi.fn();
  render(
    <MemoryRouter initialEntries={['/brand/meera']}>
      <Routes>
        <Route path="/brand/meera" element={<MeeraChatPanel onFunctionCall={onFunctionCall} />} />
        <Route path="/brand/settings/billing" element={<div>BILLING PAGE REACHED</div>} />
      </Routes>
    </MemoryRouter>,
  );
  return { onFunctionCall };
}

describe('MeeraChatPanel — credit paywall (F-0902)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    window.localStorage.clear();
    vi.mocked(meeraApi.startSession).mockResolvedValue({
      conversationId: 'conv_paywall_0001',
      status: 'ACTIVE',
      brandProfileStatus: 'READY',
      credits: { remaining: 0, unlimited: false },
    });
    vi.mocked(meeraApi.getHistory).mockResolvedValue([]);
    vi.mocked(meeraApi.sendTurn).mockRejectedValue(
      new ApiError('CREDITS_EXHAUSTED', 'No Meera credits left this month', 402),
    );
  });

  it('a CREDITS_EXHAUSTED turn shows the paywall, and its CTA reaches billing without opening the funding stage', async () => {
    const user = userEvent.setup({ delay: null });
    const { onFunctionCall } = renderPanel();

    const textbox = await screen.findByRole('textbox');
    await waitFor(() => expect(textbox).toBeEnabled());
    await user.type(textbox, 'plan my next campaign');
    await user.click(screen.getByRole('button', { name: 'Send message' }));

    // A real link, not a button that fires a stage change.
    const cta = await screen.findByRole('link', { name: MEERA_PAYWALL.cta });
    expect(cta).toHaveAttribute('href', '/brand/settings/billing');

    await user.click(cta);

    expect(await screen.findByText('BILLING PAGE REACHED')).toBeInTheDocument();
    // The dead end: request_payment with no payload parked StageFunding on its loader forever.
    expect(onFunctionCall).not.toHaveBeenCalledWith('request_payment');
    expect(onFunctionCall).not.toHaveBeenCalled();
  });

  it('the paywall copy no longer promises that funding a campaign unlocks Meera, or a fixed reset date', () => {
    const copy = `${MEERA_PAYWALL.title} ${MEERA_PAYWALL.body} ${MEERA_PAYWALL.cta}`;
    expect(copy).not.toMatch(/fund/i);
    expect(copy).not.toMatch(/\b1st\b/);
    expect(MEERA_PAYWALL.href).toBe('/brand/settings/billing');
  });
});
