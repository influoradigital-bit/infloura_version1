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
