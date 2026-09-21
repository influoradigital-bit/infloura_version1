/**
 * MeeraCopilotChat — tool-card wiring (T-MEERA-CREATOR-PHASE-B, SPEC.md §8.3).
 *
 * `CreatorToolResultRenderer` had no importer but its own test, so no tool card could ever reach a
 * creator. These pin the wiring itself, not the cards:
 *   1. tool_start → tool_result renders a card, attached to the RIGHT assistant turn (not the
 *      previous message, not the whole list).
 *   2. A tool_result that arrives BEFORE the first token still renders — the assistant bubble is
 *      created lazily, so the card has somewhere to attach instead of being dropped.
 *   3. A tool name this build has no card for is ignored with a dev-only warn, never thrown on.
 *
 * `@/lib/meera-api` and `@/lib/api` are mocked with `importOriginal` so the REAL `isCreatorToolName`
 * and the REAL §8.2 payload guards run — a hand-written stub of those would let the wiring pass
 * while the guard that actually gates each card was wrong.
 */
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { MeeraCopilotChat } from './MeeraCopilotChat';
import type { MeeraStreamHandlers } from '@/hooks/useMeeraStream';

const { openMock, closeMock, startSessionMock, getHistoryMock, sendTurnMock } = vi.hoisted(() => ({
  openMock: vi.fn(),
  closeMock: vi.fn(),
  startSessionMock: vi.fn(),
  getHistoryMock: vi.fn(),
  sendTurnMock: vi.fn(),
}));

// Only `isApiLive` is overridden — vitest.config.ts pins VITE_API_MODE=mock, and mock mode opens
// no stream at all. Everything else is the real module, because meera-api.ts imports runtime
// values from here.
vi.mock('@/lib/api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/api')>()),
  isApiLive: () => true,
}));

vi.mock('@/lib/meera-api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/meera-api')>()),
  meeraApi: {
    startSession: (...args: unknown[]) => startSessionMock(...args),
    getHistory: (...args: unknown[]) => getHistoryMock(...args),
    sendTurn: (...args: unknown[]) => sendTurnMock(...args),
  },
}));

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

/** One `get_my_deals` payload that satisfies the real `isGetMyDealsPayload` guard. */
const DEALS_PAYLOAD = {
  deals: [
    {
      deal_id: 'deal_1',
      brand_name: 'Kashmir Saffron Co',
      campaign_title: 'Harvest launch',
      status_label: 'Negotiating',
      currency: 'INR',
      secured: false,
    },
  ],
  active_count: 1,
  completed_count: 0,
};

/**
 * One `check_deal_risks` payload. `flags` carries a real flag rather than `[]` because
 * `DealRiskCard` renders nothing for an empty list by design — an empty "no risks found" panel
 * would be a claim nothing made.
 */
const RISKS_PAYLOAD = {
  flags: [
    {
      code: 'OFF_PLATFORM_PAYMENT',
      severity: 'CRITICAL' as const,
      title: 'They want to pay you outside Influora',
      detail: 'There is no protection if they do not pay.',
      action: 'Ask them to fund the deal on the platform.',
      data: {},
      dismissible: false,
    },
  ],
  highest_severity: 'CRITICAL' as const,
  target: 'DEAL' as const,
  target_id: 'deal_1',
};

/** Opens the panel, sends one message, and returns once the stream handlers are registered. */
async function openPanelAndSend(history: Array<{ id: string; role: string; content: string }> = []) {
  const user = userEvent.setup();
  startSessionMock.mockResolvedValue({ conversationId: 'conv_1' });
  getHistoryMock.mockResolvedValue(history);
  sendTurnMock.mockResolvedValue({
    messageId: 'm1',
    streamUrl: 'http://x/chat',
    streamToken: 'tok',
    workspaceId: 'ws_1',
    reply: null,
  });

  render(
    <MeeraCopilotChat firstName="Asha" language="en-IN" onClose={vi.fn()} onConsentRequired={vi.fn()} />,
  );
  await waitFor(() => expect(startSessionMock).toHaveBeenCalled());

  const textbox = await screen.findByPlaceholderText(/Ask Meera/i);
  await user.type(textbox, 'How are my deals?');
  await user.keyboard('{Enter}');
  await waitFor(() => expect(openMock).toHaveBeenCalled());
}

afterEach(() => {
  vi.clearAllMocks();
  vi.restoreAllMocks();
});

describe('MeeraCopilotChat tool cards', () => {
  it('renders a tool_start spinner, then a tool_result card on the assistant turn it belongs to', async () => {
    await openPanelAndSend([
      { id: 'h1', role: 'ASSISTANT', content: 'Earlier answer from Meera.' },
    ]);

    // A tool starts before Meera has said anything about it.
    act(() => {
      lastHandlers().onToolStart?.({ name: 'get_my_deals', input: {} });
    });
    expect(await screen.findByTestId('creator-tool-pending')).toHaveTextContent(
      'Looking up your deals…',
    );
    expect(screen.queryByTestId('my-deals-card')).toBeNull();

    // Then she narrates, and the result lands.
    act(() => {
      lastHandlers().onToken?.({ text: 'Here are your deals.' });
      lastHandlers().onToolResult?.({ name: 'get_my_deals', status: 'ok', data: DEALS_PAYLOAD });
    });

    const card = await screen.findByTestId('my-deals-card');
    expect(card).toBeInTheDocument();
    expect(within(card).getByText('Kashmir Saffron Co')).toBeInTheDocument();
    // The spinner was REPLACED, not left beside the card.
    expect(screen.queryByTestId('creator-tool-pending')).toBeNull();

    // The card hangs off the new assistant turn — not the history turn, not the creator's own.
    const turns = screen.getAllByTestId('chat-turn');
    const assistantTurn = turns[turns.length - 1];
    expect(within(assistantTurn).getByText('Here are your deals.')).toBeInTheDocument();
    expect(within(assistantTurn).getByTestId('my-deals-card')).toBeInTheDocument();

    const historyTurn = turns[0];
    expect(within(historyTurn).getByText('Earlier answer from Meera.')).toBeInTheDocument();
    expect(within(historyTurn).queryByTestId('my-deals-card')).toBeNull();

    // Exactly one card in the whole list — not one per message.
    expect(screen.getAllByTestId('my-deals-card')).toHaveLength(1);
  });

  it('still renders a tool_result that arrives before the first token', async () => {
    await openPanelAndSend();

    // No onToken at all: the assistant bubble does not exist yet. Before §8.3 this result had no
    // message to attach to and was dropped in silence.
    act(() => {
      lastHandlers().onToolResult?.({ name: 'check_deal_risks', status: 'ok', data: RISKS_PAYLOAD });
    });

    const card = await screen.findByTestId('deal-risk-card');
    expect(within(card).getByText('They want to pay you outside Influora')).toBeInTheDocument();

    // The bubble was created lazily to hold it, so the turn that closes the stream with no text
    // fills THAT bubble rather than appending a second one beside the card.
    act(() => {
      lastHandlers().onDone?.({ finish_reason: 'stop' });
    });

    const turns = screen.getAllByTestId('chat-turn');
    const assistantTurn = turns[turns.length - 1];
    expect(within(assistantTurn).getByTestId('deal-risk-card')).toBeInTheDocument();
    expect(
      within(assistantTurn).getByText(/lost my train of thought/i),
    ).toBeInTheDocument();
    expect(screen.getAllByTestId('deal-risk-card')).toHaveLength(1);
  });

  it('ignores a tool name this build has no card for, with a dev-only warn and no crash', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    await openPanelAndSend();

    act(() => {
      // A brand tool leaking onto a creator stream, or a newer AI-service build.
      lastHandlers().onToolStart?.({ name: 'create_campaign', input: {} });
      lastHandlers().onToolResult?.({ name: 'create_campaign', status: 'ok', data: { id: 'c1' } });
      lastHandlers().onToken?.({ text: 'Done.' });
    });

    expect(await screen.findByText('Done.')).toBeInTheDocument();
    // No spinner, no card, no empty shell.
    expect(screen.queryByTestId('creator-tool-pending')).toBeNull();
    expect(screen.queryByTestId('my-deals-card')).toBeNull();
    expect(warn).toHaveBeenCalledWith(
      '[MeeraCopilotChat] ignoring unknown tool name:',
      'create_campaign',
    );
  });
});
