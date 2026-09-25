/**
 * Quick-action buttons in the creator chat (2026-09-22, Swapnil, option A).
 *
 * "Write a script" and "Review my profile" never send on a tap: they put the chat box into that
 * action and the next Send goes out charged as it (action: SCRIPT / PROFILE_REVIEW, 3 credits by
 * default), with voiceReply off. "Analyse a brief" hands off to the page's brief card. With too few
 * credits (or too little of today's limit) the strip says why and Send stays off.
 */
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { MeeraCopilotChat } from './MeeraCopilotChat';
import type { CreatorCreditBalance } from '@/lib/api';

const { openMock, startSessionMock, getHistoryMock, sendTurnMock, creditsState } = vi.hoisted(() => ({
  openMock: vi.fn(),
  startSessionMock: vi.fn(),
  getHistoryMock: vi.fn(),
  sendTurnMock: vi.fn(),
  creditsState: { balance: null as CreatorCreditBalance | null },
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
}));

vi.mock('@/hooks/useCreatorCredits', () => ({
  useCreatorCredits: () => ({
    loading: false,
    enabled: creditsState.balance?.enabled === true,
    balance: creditsState.balance,
    refresh: vi.fn(),
    applyCreditsRemaining: vi.fn(),
  }),
}));

vi.mock('@/hooks/useMeeraStream', () => ({
  useMeeraStream: () => ({ status: 'idle', open: openMock, close: vi.fn(), lastError: null }),
}));

vi.mock('@/hooks/useVoiceOutput', () => ({
  useVoiceOutput: () => ({ supported: false, enabled: false, setEnabled: vi.fn(), speak: vi.fn(), stop: vi.fn() }),
}));

vi.mock('@/hooks/useVoiceInput', () => ({
  useVoiceInput: () => ({ supported: false, isListening: false, start: vi.fn(), stop: vi.fn() }),
}));

const ON: CreatorCreditBalance = {
  enabled: true,
  total: 20,
  free: 20,
  paid: 0,
  dailyUsed: 0,
  dailyCap: 30,
  costs: { turn: 1, voiceTurn: 2, brief: 3, script: 3, profileReview: 3 },
};

function renderChat(onAnalyseBrief?: () => void) {
  startSessionMock.mockResolvedValue({ conversationId: 'conv_actions' });
  getHistoryMock.mockResolvedValue([]);
  sendTurnMock.mockResolvedValue({
    messageId: 'm1',
    streamUrl: 'http://x/chat',
    streamToken: 'tok',
    workspaceId: 'ws_1',
    reply: null,
  });
  render(
    <MeeraCopilotChat
      firstName="Asha"
      language="en-IN"
      onClose={vi.fn()}
      onConsentRequired={vi.fn()}
      onAnalyseBrief={onAnalyseBrief}
    />,
  );
}

afterEach(() => {
  vi.clearAllMocks();
  creditsState.balance = null;
});

describe('MeeraCopilotChat quick actions', () => {
  it('"Write a script" does not send on tap; Send then goes out as a SCRIPT turn with the topic', async () => {
    creditsState.balance = ON;
    const user = userEvent.setup();
    renderChat();
    await waitFor(() => expect(startSessionMock).toHaveBeenCalled());

    await user.click(await screen.findByTestId('quick-action-script'));
    expect(sendTurnMock).not.toHaveBeenCalled();
    expect(screen.getByTestId('meera-action-strip')).toHaveTextContent('A script uses 3 credits');

    await user.type(screen.getByPlaceholderText(/monsoon skincare/i), 'chai recipes for winter');
    await user.click(screen.getByRole('button', { name: 'Send message' }));

    await waitFor(() => expect(sendTurnMock).toHaveBeenCalledTimes(1));
    const [, content, role, options] = sendTurnMock.mock.calls[0];
    expect(content).toBe('Write me a reel script: chai recipes for winter');
    expect(role).toBe('creator');
    expect(options).toMatchObject({ action: 'SCRIPT', voiceReply: false });
    // The chat box is back to a normal message afterwards.
    await waitFor(() => expect(screen.queryByTestId('meera-action-strip')).not.toBeInTheDocument());
  });

  it('"Review my profile" can be sent with no extra text, as a PROFILE_REVIEW turn', async () => {
    creditsState.balance = ON;
    const user = userEvent.setup();
    renderChat();
    await waitFor(() => expect(startSessionMock).toHaveBeenCalled());

    await user.click(await screen.findByTestId('quick-action-profile'));
    await user.click(screen.getByRole('button', { name: 'Send message' }));

    await waitFor(() => expect(sendTurnMock).toHaveBeenCalledTimes(1));
    const [, content, , options] = sendTurnMock.mock.calls[0];
    expect(content).toMatch(/^Review my profile\./);
    expect(options).toMatchObject({ action: 'PROFILE_REVIEW', voiceReply: false });
  });

  it('a plain message carries no action', async () => {
    creditsState.balance = ON;
    const user = userEvent.setup();
    renderChat();
    await waitFor(() => expect(startSessionMock).toHaveBeenCalled());

    await user.type(await screen.findByPlaceholderText(/Ask Meera/i), 'How are my deals?');
    await user.click(screen.getByRole('button', { name: 'Send message' }));
    await waitFor(() => expect(sendTurnMock).toHaveBeenCalledTimes(1));
    expect(sendTurnMock.mock.calls[0][3]).not.toHaveProperty('action');
  });

  it('with 2 credits the script strip says so, offers Buy, and Send stays off', async () => {
    creditsState.balance = { ...ON, total: 2 };
    const user = userEvent.setup();
    renderChat();
    await waitFor(() => expect(startSessionMock).toHaveBeenCalled());

    await user.click(await screen.findByTestId('quick-action-script'));
    expect(screen.getByTestId('meera-action-strip')).toHaveTextContent('This needs 3 credits and you have 2.');
    expect(screen.getByRole('button', { name: 'Buy credits' })).toBeInTheDocument();
    await user.type(screen.getByPlaceholderText(/monsoon skincare/i), 'anything');
    expect(screen.getByRole('button', { name: 'Send message' })).toBeDisabled();
    expect(sendTurnMock).not.toHaveBeenCalled();
  });

  it("at 28 of today's 30 the profile review is held back with the reason", async () => {
    creditsState.balance = { ...ON, dailyUsed: 28 };
    const user = userEvent.setup();
    renderChat();
    await waitFor(() => expect(startSessionMock).toHaveBeenCalled());

    await user.click(await screen.findByTestId('quick-action-profile'));
    expect(screen.getByTestId('meera-action-strip')).toHaveTextContent("today's limit has 2 left");
    expect(screen.getByRole('button', { name: 'Send message' })).toBeDisabled();
  });

  it('shows prices only when credits are on, and "Analyse a brief" hands off to the page', async () => {
    const onAnalyseBrief = vi.fn();
    const user = userEvent.setup();
    renderChat(onAnalyseBrief);
    await waitFor(() => expect(startSessionMock).toHaveBeenCalled());

    const brief = await screen.findByTestId('quick-action-brief');
    expect(brief).not.toHaveTextContent('· 3');
    await user.click(brief);
    expect(onAnalyseBrief).toHaveBeenCalledTimes(1);
    expect(sendTurnMock).not.toHaveBeenCalled();
  });

  it('no brief button when the page has no brief card', async () => {
    renderChat();
    await waitFor(() => expect(startSessionMock).toHaveBeenCalled());
    await screen.findByTestId('quick-action-script');
    expect(screen.queryByTestId('quick-action-brief')).not.toBeInTheDocument();
  });
});
