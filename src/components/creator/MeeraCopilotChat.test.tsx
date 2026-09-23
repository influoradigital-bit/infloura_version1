/**
 * MeeraCopilotChat — outage-recovery tests (fix round 1, Q4), plus the MEERA-CHAT-DESIGN-SPEC.md
 * work-trail (Part A) and "Meera is on it" desk (Part B) tests.
 *
 * Priya's Q4 finding: none of the outage cases were ever executed. These pin the three
 * FRONTEND fixes so a regression here fails a test instead of shipping an unrecoverable panel:
 *   1. onHeartbeatTimeout closes the stream, clears `sending`, and renders an honest bubble
 *      instead of leaving the panel in `sending=true` forever (a hung, not dead, Python).
 *   2. The connectError branch has a working "Try again" retry instead of "refresh to try again".
 *   3. CONNECTION_ERROR/STREAM_INCOMPLETE surface a human sentence, never the raw transport
 *      string ("Stream connection failed") useMeeraStream's `fail()` sets on `event.message`.
 *
 * `renderChat`/`rerenderChat` below wrap every render in a `MemoryRouter` — the design-spec
 * redesign added `MeeraDesk` (Part B) inside this panel's own empty state, and its tiles are
 * real `react-router-dom` `Link`s (SPEC.md Part B: "Tap → /creator/deals" etc.), which throw
 * outside a Router context.
 */
import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { MeeraCopilotChat, type MeeraCopilotChatProps } from './MeeraCopilotChat';
import type { MeeraStreamHandlers } from '@/hooks/useMeeraStream';

const { openMock, closeMock, startSessionMock, getHistoryMock, sendTurnMock } = vi.hoisted(() => ({
  openMock: vi.fn(),
  closeMock: vi.fn(),
  startSessionMock: vi.fn(),
  getHistoryMock: vi.fn(),
  sendTurnMock: vi.fn(),
}));

// MeeraDesk (Part B) reads these four directly off `@/lib/api`'s `api` namespace. Each is its
// own `vi.fn()` with NO default implementation — an un-mocked call resolves the `await` with
// `undefined`, which the desk's own per-tile try/catch (MeeraDesk.tsx) turns into "tile hidden",
// exactly like a real fetch failure would. Tests that don't care about the desk's tiles (most of
// this file) need no setup at all; the desk-specific describe block below sets real values.
const {
  dealsListMock,
  contractsUnsignedMock,
  walletGetMock,
  portfolioAnalyticsMock,
  creatorDeliverablesListForDealsMock,
} = vi.hoisted(() => ({
  dealsListMock: vi.fn(),
  contractsUnsignedMock: vi.fn(),
  walletGetMock: vi.fn(),
  portfolioAnalyticsMock: vi.fn(),
  creatorDeliverablesListForDealsMock: vi.fn(),
}));

vi.mock('@/lib/api', () => ({
  isApiLive: () => true,
  ApiError: class ApiError extends Error {
    code: string;
    constructor(code: string, message: string) {
      super(message);
      this.code = code;
    }
  },
  api: {
    deals: { list: (...args: unknown[]) => dealsListMock(...args) },
    contracts: { listUnsigned: (...args: unknown[]) => contractsUnsignedMock(...args) },
    wallet: { get: (...args: unknown[]) => walletGetMock(...args) },
    portfolio: { analytics: (...args: unknown[]) => portfolioAnalyticsMock(...args) },
    creatorDeliverables: { listForDeals: (...args: unknown[]) => creatorDeliverablesListForDealsMock(...args) },
  },
}));

// Real `isCreatorToolName`/`CREATOR_TOOL_NAMES`/payload type-guards are kept (via
// `importOriginal`) rather than replaced — `CreatorToolResultRenderer` and the new
// `MeeraWorkTrail` both call the real guards, and a full replacement mock (the file's original
// shape) silently made every one of them `undefined`, a gap this file never exercised until the
// work-trail tests below started actually firing `onToolStart`/`onToolResult`.
vi.mock('@/lib/meera-api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/meera-api')>();
  return {
    ...actual,
    meeraApi: {
      ...actual.meeraApi,
      startSession: (...args: unknown[]) => startSessionMock(...args),
      getHistory: (...args: unknown[]) => getHistoryMock(...args),
      sendTurn: (...args: unknown[]) => sendTurnMock(...args),
    },
  };
});

vi.mock('@/hooks/useMeeraStream', () => ({
  useMeeraStream: () => ({
    status: 'idle',
    open: openMock,
    close: closeMock,
    lastError: null,
  }),
}));

vi.mock('@/hooks/useVoiceOutput', () => ({
  useVoiceOutput: () => ({
    supported: false,
    enabled: false,
    setEnabled: vi.fn(),
    speak: vi.fn(),
    stop: vi.fn(),
  }),
}));

vi.mock('@/hooks/useVoiceInput', () => ({
  useVoiceInput: () => ({
    supported: false,
    isListening: false,
    start: vi.fn(),
    stop: vi.fn(),
  }),
}));

function lastHandlers(): MeeraStreamHandlers {
  const call = openMock.mock.calls[openMock.mock.calls.length - 1];
  return call[2] as MeeraStreamHandlers;
}

function renderChat(props: MeeraCopilotChatProps) {
  return render(
    <MemoryRouter>
      <MeeraCopilotChat {...props} />
    </MemoryRouter>,
  );
}

afterEach(() => {
  vi.clearAllMocks();
});

describe('MeeraCopilotChat outage recovery', () => {
  it('recovers from a heartbeat timeout instead of hanging in sending forever', async () => {
    const user = userEvent.setup();
    startSessionMock.mockResolvedValue({ conversationId: 'conv_1' });
    getHistoryMock.mockResolvedValue([]);
    sendTurnMock.mockResolvedValue({
      messageId: 'm1',
      streamUrl: 'http://x/chat',
      streamToken: 'tok',
      workspaceId: 'ws_1',
      reply: null,
    });

    renderChat({ firstName: 'Asha', language: 'en-IN', onClose: vi.fn(), onConsentRequired: vi.fn() });

    await waitFor(() => expect(startSessionMock).toHaveBeenCalled());

    const textbox = await screen.findByPlaceholderText(/Ask Meera/i);
    await user.type(textbox, 'How are my deals?');
    await user.keyboard('{Enter}');
    await waitFor(() => expect(sendTurnMock).toHaveBeenCalled());
    await waitFor(() => expect(openMock).toHaveBeenCalled());

    // MEERA-CHAT-DESIGN-SPEC.md Part A — "Understanding your question…" replaces the old plain
    // "Thinking…" line, and should be up (sending=true, no tool/token yet) before the timeout.
    expect(screen.getByText(/Understanding your question/i)).toBeInTheDocument();

    act(() => {
      lastHandlers().onHeartbeatTimeout?.();
    });

    expect(closeMock).toHaveBeenCalled();
    await waitFor(() => expect(screen.queryByText(/Understanding your question/i)).not.toBeInTheDocument());
    expect(await screen.findByText('Meera stopped responding — try again?')).toBeInTheDocument();
  });

  it('never renders the raw transport error string for CONNECTION_ERROR', async () => {
    startSessionMock.mockResolvedValue({ conversationId: 'conv_1' });
    getHistoryMock.mockResolvedValue([]);
    sendTurnMock.mockResolvedValue({
      messageId: 'm1',
      streamUrl: 'http://x/chat',
      streamToken: 'tok',
      workspaceId: 'ws_1',
      reply: null,
    });

    const user = userEvent.setup();
    renderChat({ firstName: 'Asha', language: 'en-IN', onClose: vi.fn(), onConsentRequired: vi.fn() });
    await waitFor(() => expect(startSessionMock).toHaveBeenCalled());

    const textbox = await screen.findByPlaceholderText(/Ask Meera/i);
    await user.type(textbox, 'Hi');
    await user.keyboard('{Enter}');
    await waitFor(() => expect(openMock).toHaveBeenCalled());

    act(() => {
      lastHandlers().onError?.({ code: 'CONNECTION_ERROR', fallback: 'text', message: 'Stream connection failed' });
    });

    expect(screen.queryByText('Stream connection failed')).not.toBeInTheDocument();
    expect(await screen.findByText(/Lost the connection to Meera/i)).toBeInTheDocument();
  });

  it('offers a working retry instead of "refresh to try again" when the initial connect fails', async () => {
    startSessionMock.mockRejectedValueOnce(new Error('network down'));
    const user = userEvent.setup();

    renderChat({ firstName: 'Asha', language: 'en-IN', onClose: vi.fn(), onConsentRequired: vi.fn() });

    expect(await screen.findByText("Couldn't reach Meera.")).toBeInTheDocument();
    expect(screen.queryByText(/refresh to try again/i)).not.toBeInTheDocument();

    startSessionMock.mockResolvedValueOnce({ conversationId: 'conv_2' });
    getHistoryMock.mockResolvedValueOnce([]);

    await user.click(screen.getByRole('button', { name: /try again/i }));

    await waitFor(() => expect(startSessionMock).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(screen.queryByText("Couldn't reach Meera.")).not.toBeInTheDocument());
  });
});

describe('MeeraCopilotChat — prefillMessage (U-5, R-U1)', () => {
  // Explicit setup rather than relying on leftover state from earlier tests in this file —
  // `vi.clearAllMocks()` (the file's own `afterEach`) clears call history but not implementations
  // set by `mockResolvedValue`, so these must not depend on execution order.
  beforeEach(() => {
    startSessionMock.mockResolvedValue({ conversationId: 'conv_prefill' });
    getHistoryMock.mockResolvedValue([]);
  });

  it('fills an empty composer with the prefill text and calls sendTurn zero times', async () => {
    renderChat({
      firstName: 'Asha',
      language: 'en-IN',
      onClose: vi.fn(),
      onConsentRequired: vi.fn(),
      prefillMessage: { text: 'Look at brief b_1.', token: 1 },
    });

    const textbox = await screen.findByPlaceholderText(/Ask Meera/i);
    expect(textbox).toHaveValue('Look at brief b_1.');
    // UF5-2 (PRIYA-LASTCALL-U3-U5-0917.md) — without waiting for connect to actually finish
    // (startSession -> getHistory both resolve, `connecting` goes false), this assertion runs
    // before a DELAYED auto-send (e.g. an effect gated on `connecting`) would have had any chance
    // to fire, and stays green regardless of what such a mutation does. Waiting for the real
    // connect sequence to complete first makes this assertion catch that class of bug on its own.
    await waitFor(() => expect(getHistoryMock).toHaveBeenCalled());
    expect(sendTurnMock).not.toHaveBeenCalled();
  });

  it('appends to whatever the creator already typed, never overwriting it', async () => {
    const user = userEvent.setup();
    const { rerender } = renderChat({
      firstName: 'Asha',
      language: 'en-IN',
      onClose: vi.fn(),
      onConsentRequired: vi.fn(),
      prefillMessage: null,
    });

    const textbox = await screen.findByPlaceholderText(/Ask Meera/i);
    await user.type(textbox, 'How much did Nykaa pay?');

    rerender(
      <MemoryRouter>
        <MeeraCopilotChat
          firstName="Asha"
          language="en-IN"
          onClose={vi.fn()}
          onConsentRequired={vi.fn()}
          prefillMessage={{ text: 'Look at brief b_2.', token: 1 }}
        />
      </MemoryRouter>,
    );

    await waitFor(() =>
      expect(textbox).toHaveValue('How much did Nykaa pay? Look at brief b_2.'),
    );
    expect(sendTurnMock).not.toHaveBeenCalled();
  });

  it('re-fires on a new token even with the same text, but never on a re-render with the same token', async () => {
    const user = userEvent.setup();
    const { rerender } = renderChat({
      firstName: 'Asha',
      language: 'en-IN',
      onClose: vi.fn(),
      onConsentRequired: vi.fn(),
      prefillMessage: { text: 'Look at brief b_1.', token: 1 },
    });
    const textbox = await screen.findByPlaceholderText(/Ask Meera/i);
    expect(textbox).toHaveValue('Look at brief b_1.');

    // Same token, new object identity — must NOT append again.
    rerender(
      <MemoryRouter>
        <MeeraCopilotChat
          firstName="Asha"
          language="en-IN"
          onClose={vi.fn()}
          onConsentRequired={vi.fn()}
          prefillMessage={{ text: 'Look at brief b_1.', token: 1 }}
        />
      </MemoryRouter>,
    );
    expect(textbox).toHaveValue('Look at brief b_1.');

    // The creator clears the box herself, then a genuinely NEW ask (new token) re-fills it.
    await user.clear(textbox);
    rerender(
      <MemoryRouter>
        <MeeraCopilotChat
          firstName="Asha"
          language="en-IN"
          onClose={vi.fn()}
          onConsentRequired={vi.fn()}
          prefillMessage={{ text: 'Look at brief b_1.', token: 2 }}
        />
      </MemoryRouter>,
    );
    await waitFor(() => expect(textbox).toHaveValue('Look at brief b_1.'));
  });

  it('U-5 LOW (c): a fast double-click on "Ask Meera" — two distinct tokens, same text — does not append the prompt twice', async () => {
    // The page's consent re-probe is async, so a double-click produces two DIFFERENT tokens
    // (unlike the "same token" case above) carrying the SAME prompt text, both arriving before
    // the creator has typed anything new.
    const { rerender } = renderChat({
      firstName: 'Asha',
      language: 'en-IN',
      onClose: vi.fn(),
      onConsentRequired: vi.fn(),
      prefillMessage: { text: 'Look at brief b_1.', token: 1 },
    });
    const textbox = await screen.findByPlaceholderText(/Ask Meera/i);
    expect(textbox).toHaveValue('Look at brief b_1.');

    rerender(
      <MemoryRouter>
        <MeeraCopilotChat
          firstName="Asha"
          language="en-IN"
          onClose={vi.fn()}
          onConsentRequired={vi.fn()}
          prefillMessage={{ text: 'Look at brief b_1.', token: 2 }}
        />
      </MemoryRouter>,
    );
    expect(textbox).toHaveValue('Look at brief b_1.');
  });
});

describe('MeeraCopilotChat — accessibility (N3, PRIYA-LASTCALL-U3-U5-0917.md re-check)', () => {
  it('the Send button has an accessible name (WCAG 4.1.2) findable by role and name', async () => {
    startSessionMock.mockResolvedValue({ conversationId: 'conv_a11y' });
    getHistoryMock.mockResolvedValue([]);

    renderChat({ firstName: 'Asha', language: 'en-IN', onClose: vi.fn(), onConsentRequired: vi.fn() });

    // Icon-only button — `getByRole` with a `name` only succeeds if it has a real accessible
    // name (aria-label, aria-labelledby, or text content), not just an icon.
    expect(await screen.findByRole('button', { name: 'Send message' })).toBeInTheDocument();
  });
});

describe('MeeraCopilotChat history window (cost fix 2026-09-22)', () => {
  it('sends only the last 20 messages of a long resumed chat, ending with the new one', async () => {
    const user = userEvent.setup();
    startSessionMock.mockResolvedValue({ conversationId: 'conv_long' });
    getHistoryMock.mockResolvedValue(
      Array.from({ length: 60 }, (_, i) => ({
        id: `h${i}`,
        role: i % 2 === 0 ? 'USER' : 'ASSISTANT',
        content: `old message ${i}`,
      })),
    );
    sendTurnMock.mockResolvedValue({
      messageId: 'm1',
      streamUrl: 'http://x/chat',
      streamToken: 'tok',
      workspaceId: 'ws_1',
      reply: null,
    });

    renderChat({ firstName: 'Asha', language: 'en-IN', onClose: vi.fn(), onConsentRequired: vi.fn() });
    await screen.findByText('old message 59');
    const textbox = await screen.findByPlaceholderText(/Ask Meera/i);
    await user.type(textbox, 'Write me a reel script');
    await user.keyboard('{Enter}');
    await waitFor(() => expect(openMock).toHaveBeenCalled());

    const body = openMock.mock.calls[openMock.mock.calls.length - 1][3] as {
      conversation: { role: string; content: string }[];
    };
    expect(body.conversation.length).toBeLessThanOrEqual(20);
    expect(body.conversation.length).toBeGreaterThan(10);
    expect(body.conversation[body.conversation.length - 1]).toEqual({ role: 'user', content: 'Write me a reel script' });
    expect(body.conversation[0].role).toBe('user');
  });
});

describe('MeeraCopilotChat — work trail (MEERA-CHAT-DESIGN-SPEC.md Part A)', () => {
  beforeEach(() => {
    startSessionMock.mockResolvedValue({ conversationId: 'conv_trail' });
    getHistoryMock.mockResolvedValue([]);
    sendTurnMock.mockResolvedValue({
      messageId: 'm1',
      streamUrl: 'http://x/chat',
      streamToken: 'tok',
      workspaceId: 'ws_1',
      reply: null,
    });
  });

  async function sendOneMessage(user: ReturnType<typeof userEvent.setup>) {
    renderChat({ firstName: 'Asha', language: 'en-IN', onClose: vi.fn(), onConsentRequired: vi.fn() });
    await waitFor(() => expect(startSessionMock).toHaveBeenCalled());
    const textbox = await screen.findByPlaceholderText(/Ask Meera/i);
    await user.type(textbox, 'How are my deals?');
    await user.keyboard('{Enter}');
    await waitFor(() => expect(openMock).toHaveBeenCalled());
  }

  it('shows the running label for a real tool call, then the done label on its result', async () => {
    const user = userEvent.setup();
    await sendOneMessage(user);

    act(() => {
      lastHandlers().onToolStart?.({ name: 'get_my_deals', input: {} });
    });
    expect(await screen.findByText('Reading your deals…')).toBeInTheDocument();

    act(() => {
      lastHandlers().onToolResult?.({
        name: 'get_my_deals',
        status: 'ok',
        data: { deals: [], active_count: 0, completed_count: 0 },
      });
    });
    expect(await screen.findByText('Read your deals')).toBeInTheDocument();
    expect(screen.queryByText('Reading your deals…')).not.toBeInTheDocument();
  });

  it('shows the failed label (never a raw error code) when a tool result errors', async () => {
    const user = userEvent.setup();
    await sendOneMessage(user);

    act(() => {
      lastHandlers().onToolStart?.({ name: 'get_my_metrics', input: {} });
    });
    act(() => {
      lastHandlers().onToolResult?.({
        name: 'get_my_metrics',
        status: 'error',
        data: { error: 'UPSTREAM_500', message: 'raw upstream failure' },
      });
    });

    expect(await screen.findByText("Couldn't load your numbers")).toBeInTheDocument();
    expect(screen.queryByText('UPSTREAM_500')).not.toBeInTheDocument();
  });

  it('collapses a finished turn to "Meera did N things · Show" and expands on tap', async () => {
    const user = userEvent.setup();
    await sendOneMessage(user);

    act(() => {
      lastHandlers().onToolStart?.({ name: 'get_my_deals', input: {} });
    });
    act(() => {
      lastHandlers().onToolResult?.({
        name: 'get_my_deals',
        status: 'ok',
        data: { deals: [], active_count: 0, completed_count: 0 },
      });
    });
    act(() => {
      lastHandlers().onDone?.({ finish_reason: 'stop' });
    });

    const toggle = await screen.findByRole('button', { name: /Meera did 1 thing/i });
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    // Collapsed: the live step line itself is no longer shown outside the toggle.
    expect(screen.queryByText('Read your deals')).not.toBeInTheDocument();

    await user.click(toggle);
    expect(toggle).toHaveAttribute('aria-expanded', 'true');
    expect(await screen.findByText('Read your deals')).toBeInTheDocument();
  });

  it('shows exactly one "Understanding your question…" step before any tool event or token, which then disappears on the first token', async () => {
    const user = userEvent.setup();
    await sendOneMessage(user);

    expect(screen.getByText(/Understanding your question/i)).toBeInTheDocument();

    act(() => {
      lastHandlers().onToken?.({ text: 'Hello' });
    });

    await waitFor(() =>
      expect(screen.queryByText(/Understanding your question/i)).not.toBeInTheDocument(),
    );
  });

  it('shows no trail line at all for a turn with zero tool calls', async () => {
    const user = userEvent.setup();
    await sendOneMessage(user);

    act(() => {
      lastHandlers().onToken?.({ text: 'Everything looks good!' });
    });
    act(() => {
      lastHandlers().onDone?.({ finish_reason: 'stop' });
    });

    expect(await screen.findByText('Everything looks good!')).toBeInTheDocument();
    expect(screen.queryByText(/Meera did/i)).not.toBeInTheDocument();
    expect(screen.queryByTestId('work-trail')).not.toBeInTheDocument();
  });
});

describe('MeeraCopilotChat — "Meera is on it" desk (MEERA-CHAT-DESIGN-SPEC.md Part B)', () => {
  beforeEach(() => {
    startSessionMock.mockResolvedValue({ conversationId: 'conv_desk' });
    getHistoryMock.mockResolvedValue([]);
  });

  it('renders tiles from mocked api values, and a rejected call hides only its own tile', async () => {
    dealsListMock.mockResolvedValue([
      {
        id: 'd1',
        campaignId: 'c1',
        campaignName: 'Diwali collab',
        counterpartyId: 'brand_1',
        counterpartyName: 'Nykaa',
        status: 'IN_NEGOTIATION',
        dealValue: 20000,
        currency: 'INR',
        unreadCount: 3,
        deliverablesDone: 0,
        deliverablesTotal: 2,
        escrowFunded: false,
      },
    ]);
    contractsUnsignedMock.mockResolvedValue([{ id: 'ct1' }]);
    walletGetMock.mockRejectedValue(new Error('wallet down'));
    portfolioAnalyticsMock.mockResolvedValue({
      pageViews: { last30Days: 500, deltaPercent: 12 },
      profileClicks: 10,
      profileClicksEstimated: true,
      linkClicks: [],
      brandInquiries: 2,
      mediaKitDownloads: 1,
    });

    renderChat({ firstName: 'Asha', language: 'en-IN', onClose: vi.fn(), onConsentRequired: vi.fn() });

    // Tile 1: 3 unread + 1 unsigned contract = 4. The deal is IN_NEGOTIATION — not `isActiveDeal`
    // — so its deliverables are never asked for (0 added), matching the dashboard's own rule.
    const attentionTile = await screen.findByTestId('desk-tile-attention');
    expect(attentionTile).toHaveTextContent('4');
    expect(creatorDeliverablesListForDealsMock).not.toHaveBeenCalled();

    // Tile 2 (wallet) rejected — hidden, never a fabricated ₹0.
    await waitFor(() => expect(walletGetMock).toHaveBeenCalled());
    expect(screen.queryByTestId('desk-tile-wallet')).not.toBeInTheDocument();

    // Tile 3: real analytics value came back.
    const engagementTile = await screen.findByTestId('desk-tile-engagement');
    expect(engagementTile).toHaveTextContent('500');

    // Tile 4 always renders — it never fetches anything, it only prefills.
    expect(screen.getByTestId('desk-tile-ask-rate')).toBeInTheDocument();
  });

  it('a starter prompt prefills the composer and never calls sendTurn', async () => {
    dealsListMock.mockResolvedValue([]);
    contractsUnsignedMock.mockResolvedValue([]);
    walletGetMock.mockResolvedValue({ availableBalance: 0, escrowLocked: 0, pendingPayouts: 0, runwayDays: null });
    portfolioAnalyticsMock.mockRejectedValue(new Error('no analytics'));

    const user = userEvent.setup();
    renderChat({ firstName: 'Asha', language: 'en-IN', onClose: vi.fn(), onConsentRequired: vi.fn() });

    const prompt = await screen.findByRole('button', { name: 'How are my deals doing?' });
    await user.click(prompt);

    const textbox = await screen.findByPlaceholderText(/Ask Meera/i);
    expect(textbox).toHaveValue('How are my deals doing?');
    expect(sendTurnMock).not.toHaveBeenCalled();
  });

  it('"Ask Meera my rate" prefills the exact rate question and never calls sendTurn', async () => {
    dealsListMock.mockRejectedValue(new Error('down'));
    contractsUnsignedMock.mockRejectedValue(new Error('down'));
    walletGetMock.mockRejectedValue(new Error('down'));
    portfolioAnalyticsMock.mockRejectedValue(new Error('down'));

    const user = userEvent.setup();
    renderChat({ firstName: 'Asha', language: 'en-IN', onClose: vi.fn(), onConsentRequired: vi.fn() });

    const askRate = await screen.findByTestId('desk-tile-ask-rate');
    await user.click(askRate);

    const textbox = await screen.findByPlaceholderText(/Ask Meera/i);
    expect(textbox).toHaveValue('What rate should I charge for a reel?');
    expect(sendTurnMock).not.toHaveBeenCalled();
  });

  it('disappears once the creator sends their first message, and never comes back this session', async () => {
    dealsListMock.mockRejectedValue(new Error('down'));
    contractsUnsignedMock.mockRejectedValue(new Error('down'));
    walletGetMock.mockRejectedValue(new Error('down'));
    portfolioAnalyticsMock.mockRejectedValue(new Error('down'));
    sendTurnMock.mockResolvedValue({
      messageId: 'm1',
      streamUrl: 'http://x/chat',
      streamToken: 'tok',
      workspaceId: 'ws_1',
      reply: null,
    });

    const user = userEvent.setup();
    renderChat({ firstName: 'Asha', language: 'en-IN', onClose: vi.fn(), onConsentRequired: vi.fn() });

    expect(await screen.findByTestId('meera-desk')).toBeInTheDocument();

    const textbox = await screen.findByPlaceholderText(/Ask Meera/i);
    await user.type(textbox, 'How are my deals?');
    await user.keyboard('{Enter}');

    await waitFor(() => expect(screen.queryByTestId('meera-desk')).not.toBeInTheDocument());

    act(() => {
      lastHandlers().onToken?.({ text: 'Looking good!' });
    });
    act(() => {
      lastHandlers().onDone?.({ finish_reason: 'stop' });
    });

    expect(screen.queryByTestId('meera-desk')).not.toBeInTheDocument();
  });
});

describe('MeeraCopilotChat — Send button (Round 2 QA: never washed out)', () => {
  beforeEach(() => {
    startSessionMock.mockResolvedValue({ conversationId: 'conv_send' });
    getHistoryMock.mockResolvedValue([]);
  });

  it('is never `disabled` (and so never dimmed) while the composer is empty', async () => {
    renderChat({ firstName: 'Asha', language: 'en-IN', onClose: vi.fn(), onConsentRequired: vi.fn() });

    const send = await screen.findByRole('button', { name: 'Send message' });
    await waitFor(() => expect(send).not.toBeDisabled());
  });

  it('tapping Send on an empty composer focuses the textarea instead of sending', async () => {
    const user = userEvent.setup();
    renderChat({ firstName: 'Asha', language: 'en-IN', onClose: vi.fn(), onConsentRequired: vi.fn() });

    const textbox = await screen.findByPlaceholderText(/Ask Meera/i);
    const send = await screen.findByRole('button', { name: 'Send message' });
    expect(textbox).not.toHaveFocus();

    await user.click(send);

    expect(textbox).toHaveFocus();
    expect(sendTurnMock).not.toHaveBeenCalled();
  });

  it('shows a spinner (still solid, not disabled) while sending, and a second tap does not double-send', async () => {
    sendTurnMock.mockResolvedValue({
      messageId: 'm1',
      streamUrl: 'http://x/chat',
      streamToken: 'tok',
      workspaceId: 'ws_1',
      reply: null,
    });
    const user = userEvent.setup();
    renderChat({ firstName: 'Asha', language: 'en-IN', onClose: vi.fn(), onConsentRequired: vi.fn() });

    const textbox = await screen.findByPlaceholderText(/Ask Meera/i);
    await user.type(textbox, 'How are my deals?');
    const send = await screen.findByRole('button', { name: 'Send message' });
    await user.click(send);

    await waitFor(() => expect(sendTurnMock).toHaveBeenCalledTimes(1));
    expect(await screen.findByTestId('send-spinner')).toBeInTheDocument();
    expect(send).not.toBeDisabled();

    // A second tap while still sending must not fire a second turn.
    await user.click(send);
    expect(sendTurnMock).toHaveBeenCalledTimes(1);
  });
});
