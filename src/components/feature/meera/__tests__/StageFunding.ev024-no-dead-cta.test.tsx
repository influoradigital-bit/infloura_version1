/**
 * EV-024 — Meera must not offer a funding action she cannot complete.
 *
 * The defect: the Living Canvas's funding stage rendered a "Fund & go live"
 * button whose handler called `api.payments.fundEscrow(MEERA_DEMO_CAMPAIGN_ID)`
 * with the literal string 'meera_demo_campaign'. Against a live server that can
 * only fail. And the authoritative amount the button was supposed to sit under
 * comes from a `request_payment` tool result, which cannot arrive at all:
 * `OnBehalfTokenService.SCOPE_DEFAULT` deliberately excludes both money tools,
 * so the stage's other live branch was a "Securing your funds…" spinner that
 * never resolved — a progress indicator for work nobody had started.
 *
 * The fix: in live mode the stage hands the step over to the control that
 * really does it (the wallet's Secure Campaign Funds card), using the same
 * hand-off card the chat transcript already shows.
 *
 * Run: npx vitest run src/components/feature/meera/__tests__/StageFunding.ev024-no-dead-cta.test.tsx
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';

if (!('scrollTo' in Element.prototype)) {
  Object.defineProperty(Element.prototype, 'scrollTo', {
    value: () => {},
    writable: true,
    configurable: true,
  });
}

const isApiLive = vi.hoisted(() => vi.fn(() => false));
const fundEscrow = vi.hoisted(() => vi.fn());

vi.mock('@/lib/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/api')>();
  return {
    ...actual,
    isApiLive,
    default: { ...actual.default, payments: { ...actual.default.payments, fundEscrow } },
  };
});

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

import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

import { StageFunding } from '@/components/feature/meera/StageFunding';
import { MeeraWorkspace } from '@/components/feature/meera/MeeraWorkspace';

function renderStage(props: Partial<React.ComponentProps<typeof StageFunding>> = {}) {
  return render(
    <MemoryRouter>
      <StageFunding paid={false} onPay={props.onPay ?? vi.fn()} onGoLive={vi.fn()} {...props} />
    </MemoryRouter>,
  );
}

beforeEach(() => {
  isApiLive.mockReturnValue(false);
  fundEscrow.mockReset();
});

afterEach(() => {
  vi.clearAllMocks();
});

describe('EV-024 — the funding stage never offers a dead action', () => {
  it('LIVE: offers no funding control at all, and points at the control that works', () => {
    isApiLive.mockReturnValue(true);

    renderStage();

    // No button that claims it will fund anything.
    const funders = screen
      .queryAllByRole('button')
      .filter((b) => /fund|secure|pay/i.test(b.textContent ?? ''));
    expect(funders).toEqual([]);

    // And no fake progress: the old failure mode was an endless spinner.
    expect(screen.queryByText(/securing your funds/i)).not.toBeInTheDocument();

    // Instead: the honest hand-off, with a real destination.
    expect(screen.getByText(/securing the funds is your step/i)).toBeInTheDocument();
    const link = screen.getByRole('link', { name: /open your wallet/i });
    expect(link).toHaveAttribute('href', '/brand/wallet');
  });

  it('LIVE: nothing in the stage can reach the money endpoint', async () => {
    isApiLive.mockReturnValue(true);
    const onPay = vi.fn();

    renderStage({ onPay });

    // Click everything the stage rendered; none of it may start a payment.
    for (const el of [...screen.queryAllByRole('button'), ...screen.queryAllByRole('link')]) {
      await userEvent.click(el);
    }
    expect(onPay).not.toHaveBeenCalled();
    expect(fundEscrow).not.toHaveBeenCalled();
  });

  it('MOCK: the demo flow is untouched — the pay control is still there', () => {
    // FALSIFICATION. "Render nothing, ever" would pass both tests above. Mock
    // mode exists to demonstrate the flow end to end and its fundEscrow moves
    // no money, so the control must survive.
    isApiLive.mockReturnValue(false);

    renderStage();

    const funders = screen
      .queryAllByRole('button')
      .filter((b) => /fund|secure|pay/i.test(b.textContent ?? ''));
    expect(funders.length).toBeGreaterThan(0);
    expect(screen.queryByText(/securing the funds is your step/i)).not.toBeInTheDocument();
  });
});

describe('EV-024 — the placeholder campaign id cannot reach a live server', () => {
  it('LIVE: MeeraWorkspace never sends the demo campaign id to fundEscrow', async () => {
    isApiLive.mockReturnValue(true);

    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false, gcTime: 0 } },
    });
    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter>
          <MeeraWorkspace />
        </MemoryRouter>
      </QueryClientProvider>,
    );

    // Whatever the canvas is showing, clicking through it must not start a
    // payment for a campaign that does not exist. This is the second line of
    // defence behind the stage change above: even if some future canvas state
    // calls `onPay`, the handler refuses in live mode.
    const buttons = screen.queryAllByRole('button');
    // Non-vacuous: the workspace really did render an interactive canvas. Without
    // this, a render that threw and produced nothing would "pass".
    expect(buttons.length).toBeGreaterThan(0);
    for (const button of buttons) {
      await userEvent.click(button);
    }

    expect(fundEscrow).not.toHaveBeenCalled();
  });
});
