/**
 * MeeraCopilotChat — credits wiring (T-CREATOR-CREDITS-V2, SPEC.md §9.3).
 *
 * A47 (R6, "never silent on a refusal"): a 402/429 from the credits ledger
 * (`CREATOR_CREDITS_EXHAUSTED` / `CREATOR_DAILY_CAP_REACHED`) must render the server's own
 * en/hi-templated `message` verbatim as a Meera bubble, PLUS a `BuyCreditsCard` right under it —
 * never a generic failure sentence, never nothing at all.
 *
 * A50 (SPEC.md §9.3 F4): `speak()` is called with the turn's own `messageId` as its `turnId`
 * (3rd argument) for BOTH reply shapes this panel can receive — an immediate (non-streamed)
 * `reply` AND a streamed reply that only resolves on the SSE `done` event — so a
 * `CREATOR_CREDITS_ENABLED` backend can bind the Sarvam TTS call to the same turn that a voice
 * reply doubles the credit cost of (SPEC.md §7.2).
 *
 * Run: npx vitest run src/components/creator/MeeraCopilotChat.credits.test.tsx
 */
import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { MeeraCopilotChat } from './MeeraCopilotChat';
import { ApiError } from '@/lib/api';
import type { MeeraStreamHandlers } from '@/hooks/useMeeraStream';

const { openMock, closeMock, startSessionMock, getHistoryMock, sendTurnMock, speakMock } = vi.hoisted(() => ({
  openMock: vi.fn(),
  closeMock: vi.fn(),
  startSessionMock: vi.fn(),
  getHistoryMock: vi.fn(),
  sendTurnMock: vi.fn(),
  speakMock: vi.fn(),
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
}));

vi.mock('@/lib/meera-api', async () => ({
  // The chat's pure photo-check helpers, real (no network): replay neutraliser, history card.
  neutralisePhotoCheckHeader: (await vi.importActual<typeof import('@/lib/meera-api')>('@/lib/meera-api'))
    .neutralisePhotoCheckHeader,
  photoCheckFromHistoryCard: (await vi.importActual<typeof import('@/lib/meera-api')>('@/lib/meera-api'))
    .photoCheckFromHistoryCard,
  meeraApi: {
    startSession: (...args: unknown[]) => startSessionMock(...args),
    getHistory: (...args: unknown[]) => getHistoryMock(...args),
    sendTurn: (...args: unknown[]) => sendTurnMock(...args),
  },
  // Not exercised by these tests (no tool_start/tool_result events are fired), but the component
  // imports it unconditionally — keep the mock module shape complete rather than relying on it
  // never being called.
  isCreatorToolName: () => false,
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
    supported: true,
    enabled: true,
    setEnabled: vi.fn(),
    isSpeaking: false,
    speak: speakMock,
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

/** Connects the panel and sends one creator message, returning once `sendTurn` was called. */
async function connectAndSend(text: string) {
  startSessionMock.mockResolvedValue({ conversationId: 'conv_1' });
  getHistoryMock.mockResolvedValue([]);

  const user = userEvent.setup();
  render(<MeeraCopilotChat firstName="Asha" language="en-IN" onClose={vi.fn()} onConsentRequired={vi.fn()} />);
  await waitFor(() => expect(startSessionMock).toHaveBeenCalled());

  const textbox = await screen.findByPlaceholderText(/Ask Meera/i);
  await user.type(textbox, text);
  await user.keyboard('{Enter}');
  await waitFor(() => expect(sendTurnMock).toHaveBeenCalled());
}

afterEach(() => {
  vi.clearAllMocks();
});

describe('MeeraCopilotChat — credits refusal (A47, R6 "never silent")', () => {
  it('a CREATOR_CREDITS_EXHAUSTED refusal renders the server message verbatim plus a Buy Credits card', async () => {
    const serverMessage =
      "You're out of credits, so I can't answer this one yet. Buy 60 credits to keep chatting.";
    sendTurnMock.mockRejectedValue(new ApiError('CREATOR_CREDITS_EXHAUSTED', serverMessage));

    await connectAndSend('How much would this deal pay?');

    expect(await screen.findByText(serverMessage)).toBeInTheDocument();
    expect(screen.getByTestId('buy-credits-card')).toBeInTheDocument();
  });

  it('a CREATOR_DAILY_CAP_REACHED refusal ALSO renders the server message verbatim plus a Buy Credits card', async () => {
    const serverMessage =
      "You've used today's 30 credits. Your limit resets at midnight (IST). Credits you buy now stay in your balance for tomorrow.";
    sendTurnMock.mockRejectedValue(new ApiError('CREATOR_DAILY_CAP_REACHED', serverMessage));

    await connectAndSend('Any tips for my next post?');

    expect(await screen.findByText(serverMessage)).toBeInTheDocument();
    expect(screen.getByTestId('buy-credits-card')).toBeInTheDocument();
  });

  it('a non-credits failure still never goes silent, but does NOT show the Buy Credits card', async () => {
    sendTurnMock.mockRejectedValue(new Error('boom'));

    await connectAndSend('Hello?');

    expect(await screen.findByText('Something went wrong sending that — try again?')).toBeInTheDocument();
    expect(screen.queryByTestId('buy-credits-card')).not.toBeInTheDocument();
  });

  it('the pre-existing CREATOR_MONTHLY_CAP_REACHED (unrelated USD spend-tracker cap) shows its OWN message and no Buy Credits card', async () => {
    // Guards against a regression that widens isCreditsRefusal() to swallow this unrelated code
    // too (see the component's own doc note distinguishing the two cap families).
    sendTurnMock.mockRejectedValue(new ApiError('CREATOR_MONTHLY_CAP_REACHED', "You've reached your monthly Meera usage limit."));

    await connectAndSend('One more question');

    expect(await screen.findByText("You've reached your monthly Meera usage limit.")).toBeInTheDocument();
    expect(screen.queryByTestId('buy-credits-card')).not.toBeInTheDocument();
  });
});

describe('MeeraCopilotChat — speak() carries turnId (A50)', () => {
  it('an immediate (non-streamed) reply speaks with the turn\'s own messageId as turnId', async () => {
    sendTurnMock.mockResolvedValue({
      messageId: 'm_77',
      streamUrl: '',
      streamToken: '',
      workspaceId: 'ws_1',
      reply: 'Hey! Your rate looks solid for that niche.',
      creditsRemaining: 58,
    });

    await connectAndSend('What should I charge for this?');

    await waitFor(() => expect(speakMock).toHaveBeenCalled());
    expect(speakMock).toHaveBeenCalledWith('Hey! Your rate looks solid for that niche.', 'en-IN', 'm_77');
  });

  it('a streamed reply speaks with the SAME turn\'s messageId once the stream completes (onDone)', async () => {
    sendTurnMock.mockResolvedValue({
      messageId: 'm_88',
      streamUrl: 'http://x/chat',
      streamToken: 'tok',
      workspaceId: 'ws_1',
      reply: null,
    });

    await connectAndSend('Tell me about my last deal');
    await waitFor(() => expect(openMock).toHaveBeenCalled());

    act(() => {
      lastHandlers().onToken?.({ text: 'Sure thing! ' });
    });
    act(() => {
      lastHandlers().onToken?.({ text: 'Here is a summary.' });
    });
    act(() => {
      lastHandlers().onDone?.({ finish_reason: 'stop' });
    });

    expect(speakMock).toHaveBeenCalledWith('Sure thing! Here is a summary.', 'en-IN', 'm_88');
  });

  it('a DIFFERENT turn gets a DIFFERENT turnId — the id is not just hardcoded/stale from the first call', async () => {
    sendTurnMock
      .mockResolvedValueOnce({
        messageId: 'm_first',
        streamUrl: '',
        streamToken: '',
        workspaceId: 'ws_1',
        reply: 'First reply.',
      })
      .mockResolvedValueOnce({
        messageId: 'm_second',
        streamUrl: '',
        streamToken: '',
        workspaceId: 'ws_1',
        reply: 'Second reply.',
      });

    startSessionMock.mockResolvedValue({ conversationId: 'conv_1' });
    getHistoryMock.mockResolvedValue([]);
    const user = userEvent.setup();
    render(<MeeraCopilotChat firstName="Asha" language="en-IN" onClose={vi.fn()} onConsentRequired={vi.fn()} />);
    await waitFor(() => expect(startSessionMock).toHaveBeenCalled());

    const textbox = await screen.findByPlaceholderText(/Ask Meera/i);
    await user.type(textbox, 'First question');
    await user.keyboard('{Enter}');
    await waitFor(() => expect(speakMock).toHaveBeenCalledWith('First reply.', 'en-IN', 'm_first'));

    await user.type(textbox, 'Second question');
    await user.keyboard('{Enter}');
    await waitFor(() => expect(speakMock).toHaveBeenCalledWith('Second reply.', 'en-IN', 'm_second'));
  });
});
