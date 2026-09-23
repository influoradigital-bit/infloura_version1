/**
 * MeeraCopilotChat — outage-recovery tests (fix round 1, Q4).
 *
 * Priya's Q4 finding: none of the outage cases were ever executed. These pin the three
 * FRONTEND fixes so a regression here fails a test instead of shipping an unrecoverable panel:
 *   1. onHeartbeatTimeout closes the stream, clears `sending`, and renders an honest bubble
 *      instead of leaving the panel in `sending=true` forever (a hung, not dead, Python).
 *   2. The connectError branch has a working "Try again" retry instead of "refresh to try again".
 *   3. CONNECTION_ERROR/STREAM_INCOMPLETE surface a human sentence, never the raw transport
 *      string ("Stream connection failed") useMeeraStream's `fail()` sets on `event.message`.
 */
import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { MeeraCopilotChat } from './MeeraCopilotChat';
import type { MeeraStreamHandlers } from '@/hooks/useMeeraStream';

const { openMock, closeMock, startSessionMock, getHistoryMock, sendTurnMock } = vi.hoisted(() => ({
  openMock: vi.fn(),
  closeMock: vi.fn(),
  startSessionMock: vi.fn(),
  getHistoryMock: vi.fn(),
  sendTurnMock: vi.fn(),
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

vi.mock('@/lib/meera-api', () => ({
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

    render(
      <MeeraCopilotChat firstName="Asha" language="en-IN" onClose={vi.fn()} onConsentRequired={vi.fn()} />,
    );

    await waitFor(() => expect(startSessionMock).toHaveBeenCalled());

    const textbox = await screen.findByPlaceholderText(/Ask Meera/i);
    await user.type(textbox, 'How are my deals?');
    await user.keyboard('{Enter}');
    await waitFor(() => expect(sendTurnMock).toHaveBeenCalled());
    await waitFor(() => expect(openMock).toHaveBeenCalled());

    // "Thinking…" indicator should be up (sending=true) before the timeout fires.
    expect(screen.getByText(/Thinking…/i)).toBeInTheDocument();

    act(() => {
      lastHandlers().onHeartbeatTimeout?.();
    });

    expect(closeMock).toHaveBeenCalled();
    await waitFor(() => expect(screen.queryByText(/Thinking…/i)).not.toBeInTheDocument());
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
    render(
      <MeeraCopilotChat firstName="Asha" language="en-IN" onClose={vi.fn()} onConsentRequired={vi.fn()} />,
    );
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

    render(
      <MeeraCopilotChat firstName="Asha" language="en-IN" onClose={vi.fn()} onConsentRequired={vi.fn()} />,
    );

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
    render(
      <MeeraCopilotChat
        firstName="Asha"
        language="en-IN"
        onClose={vi.fn()}
        onConsentRequired={vi.fn()}
        prefillMessage={{ text: 'Look at brief b_1.', token: 1 }}
      />,
    );

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
    const { rerender } = render(
      <MeeraCopilotChat
        firstName="Asha"
        language="en-IN"
        onClose={vi.fn()}
        onConsentRequired={vi.fn()}
        prefillMessage={null}
      />,
    );

    const textbox = await screen.findByPlaceholderText(/Ask Meera/i);
    await user.type(textbox, 'How much did Nykaa pay?');

    rerender(
      <MeeraCopilotChat
        firstName="Asha"
        language="en-IN"
        onClose={vi.fn()}
        onConsentRequired={vi.fn()}
        prefillMessage={{ text: 'Look at brief b_2.', token: 1 }}
      />,
    );

    await waitFor(() =>
      expect(textbox).toHaveValue('How much did Nykaa pay? Look at brief b_2.'),
    );
    expect(sendTurnMock).not.toHaveBeenCalled();
  });

  it('re-fires on a new token even with the same text, but never on a re-render with the same token', async () => {
    const user = userEvent.setup();
    const { rerender } = render(
      <MeeraCopilotChat
        firstName="Asha"
        language="en-IN"
        onClose={vi.fn()}
        onConsentRequired={vi.fn()}
        prefillMessage={{ text: 'Look at brief b_1.', token: 1 }}
      />,
    );
    const textbox = await screen.findByPlaceholderText(/Ask Meera/i);
    expect(textbox).toHaveValue('Look at brief b_1.');

    // Same token, new object identity — must NOT append again.
    rerender(
      <MeeraCopilotChat
        firstName="Asha"
        language="en-IN"
        onClose={vi.fn()}
        onConsentRequired={vi.fn()}
        prefillMessage={{ text: 'Look at brief b_1.', token: 1 }}
      />,
    );
    expect(textbox).toHaveValue('Look at brief b_1.');

    // The creator clears the box herself, then a genuinely NEW ask (new token) re-fills it.
    await user.clear(textbox);
    rerender(
      <MeeraCopilotChat
        firstName="Asha"
        language="en-IN"
        onClose={vi.fn()}
        onConsentRequired={vi.fn()}
        prefillMessage={{ text: 'Look at brief b_1.', token: 2 }}
      />,
    );
    await waitFor(() => expect(textbox).toHaveValue('Look at brief b_1.'));
  });

  it('U-5 LOW (c): a fast double-click on "Ask Meera" — two distinct tokens, same text — does not append the prompt twice', async () => {
    // The page's consent re-probe is async, so a double-click produces two DIFFERENT tokens
    // (unlike the "same token" case above) carrying the SAME prompt text, both arriving before
    // the creator has typed anything new.
    const { rerender } = render(
      <MeeraCopilotChat
        firstName="Asha"
        language="en-IN"
        onClose={vi.fn()}
        onConsentRequired={vi.fn()}
        prefillMessage={{ text: 'Look at brief b_1.', token: 1 }}
      />,
    );
    const textbox = await screen.findByPlaceholderText(/Ask Meera/i);
    expect(textbox).toHaveValue('Look at brief b_1.');

    rerender(
      <MeeraCopilotChat
        firstName="Asha"
        language="en-IN"
        onClose={vi.fn()}
        onConsentRequired={vi.fn()}
        prefillMessage={{ text: 'Look at brief b_1.', token: 2 }}
      />,
    );
    expect(textbox).toHaveValue('Look at brief b_1.');
  });
});

describe('MeeraCopilotChat — accessibility (N3, PRIYA-LASTCALL-U3-U5-0917.md re-check)', () => {
  it('the Send button has an accessible name (WCAG 4.1.2) findable by role and name', async () => {
    startSessionMock.mockResolvedValue({ conversationId: 'conv_a11y' });
    getHistoryMock.mockResolvedValue([]);

    render(
      <MeeraCopilotChat firstName="Asha" language="en-IN" onClose={vi.fn()} onConsentRequired={vi.fn()} />,
    );

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

    render(
      <MeeraCopilotChat firstName="Asha" language="en-IN" onClose={vi.fn()} onConsentRequired={vi.fn()} />,
    );
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
