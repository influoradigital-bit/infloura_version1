/**
 * T-MEERA-CREATOR-PHASE-C (PHASE-C-SPEC.md §3/§4) — the chat's result-card wiring.
 *
 * Mirrors `MeeraCopilotChat.toolcards.test.tsx`'s mocking setup (same `useMeeraStream`/`meeraApi`
 * mocks) so `lastHandlers()` can drive the SAME stream events a real turn would fire, rather than
 * asserting against a hand-built `ChatMessage` the component itself never produces.
 */
import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
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

const SCRIPT_TEXT = [
  'SCRIPT',
  'Title: 3 saffron mistakes to avoid',
  'Length: 30s',
  'Hook: Stop buying saffron until you watch this',
  '0-10s: Show the box, ask "is your saffron real?"',
  '10-20s: Do the warm-water color test on camera',
  '20-30s: Show the certificate and say why it matters',
  'CTA: Link in bio for real Kashmiri saffron',
  'Why: Problem-agitate-solve structure, curiosity-gap hook template',
].join('\n');

async function openPanelAndSend(prompt = 'Write me a reel script') {
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
    <MemoryRouter>
      <MeeraCopilotChat firstName="Asha" language="en-IN" onClose={vi.fn()} onConsentRequired={vi.fn()} />
    </MemoryRouter>,
  );
  await waitFor(() => expect(startSessionMock).toHaveBeenCalled());

  const textbox = await screen.findByPlaceholderText(/Ask Meera/i);
  await user.type(textbox, prompt);
  await user.keyboard('{Enter}');
  await waitFor(() => expect(openMock).toHaveBeenCalled());
  return user;
}

afterEach(() => {
  vi.clearAllMocks();
  vi.restoreAllMocks();
});

describe('MeeraCopilotChat — result cards (PHASE-C-SPEC.md §3)', () => {
  it('does not render a card from partial streaming text, only after onDone', async () => {
    await openPanelAndSend();

    // `onToken.text` ACCUMULATES (MeeraCopilotChat's `assistantText += event.text`), the same way
    // a real SSE stream arrives in pieces — so the two chunks below are split from, and together
    // reconstruct, the exact same SCRIPT_TEXT the assertions check against.
    const splitAt = SCRIPT_TEXT.indexOf('0-10s');
    const firstChunk = SCRIPT_TEXT.slice(0, splitAt); // ends mid-way through the beats — unparseable
    const secondChunk = SCRIPT_TEXT.slice(splitAt);

    act(() => {
      lastHandlers().onToken?.({ text: firstChunk });
    });
    expect(screen.queryByTestId('meera-script-card')).toBeNull();

    // The full, valid script text has now streamed in — but the turn has not finished yet.
    act(() => {
      lastHandlers().onToken?.({ text: secondChunk });
    });
    expect(screen.queryByTestId('meera-script-card')).toBeNull();
    // `collapseWhitespace: false` — the default normalizer does not reliably match a string that
    // itself contains embedded newlines (confirmed against a minimal RTL repro); the bubble's
    // real, un-normalized `\n`-joined text is compared as-is instead.
    expect(screen.getByText(SCRIPT_TEXT, { collapseWhitespace: false })).toBeInTheDocument();

    act(() => {
      lastHandlers().onDone?.({ finish_reason: 'stop' });
    });

    const card = await screen.findByTestId('meera-script-card');
    expect(card).toHaveTextContent('3 saffron mistakes to avoid');
    // The raw bubble is replaced BY the card, not shown alongside it.
    expect(screen.queryByText(SCRIPT_TEXT, { collapseWhitespace: false })).toBeNull();
  });

  it('still renders a plain bubble for a normal (non-script, non-review) answer', async () => {
    await openPanelAndSend('How are my deals doing?');

    act(() => {
      lastHandlers().onToken?.({ text: 'Your two active deals are both on track.' });
      lastHandlers().onDone?.({ finish_reason: 'stop' });
    });

    expect(await screen.findByText('Your two active deals are both on track.')).toBeInTheDocument();
    expect(screen.queryByTestId('meera-script-card')).toBeNull();
    expect(screen.queryByTestId('meera-review-card')).toBeNull();
    expect(screen.queryByTestId('result-card-text-toggle')).toBeNull();
  });

  it('renders a malformed script as plain text, never a broken card', async () => {
    await openPanelAndSend();

    const malformed = 'SCRIPT\nTitle: Missing everything else';
    act(() => {
      lastHandlers().onToken?.({ text: malformed });
      lastHandlers().onDone?.({ finish_reason: 'stop' });
    });

    expect(await screen.findByText(malformed, { collapseWhitespace: false })).toBeInTheDocument();
    expect(screen.queryByTestId('meera-script-card')).toBeNull();
    expect(screen.queryByTestId('result-card-text-toggle')).toBeNull();
  });

  it('"Show as text" reveals the original text unchanged, and toggles back to the card', async () => {
    const user = await openPanelAndSend();

    act(() => {
      lastHandlers().onToken?.({ text: SCRIPT_TEXT });
      lastHandlers().onDone?.({ finish_reason: 'stop' });
    });
    await screen.findByTestId('meera-script-card');

    const toggle = screen.getByTestId('result-card-text-toggle');
    expect(toggle).toHaveTextContent('Show as text');
    await user.click(toggle);

    expect(screen.queryByTestId('meera-script-card')).toBeNull();
    expect(screen.getByText(SCRIPT_TEXT, { collapseWhitespace: false })).toBeInTheDocument();

    const toggleBack = screen.getByTestId('result-card-text-toggle');
    expect(toggleBack).toHaveTextContent('Show as card');
    await user.click(toggleBack);

    expect(await screen.findByTestId('meera-script-card')).toBeInTheDocument();
    expect(screen.queryByText(SCRIPT_TEXT, { collapseWhitespace: false })).toBeNull();
  });

  it('a review reply renders MeeraReviewCard after onDone', async () => {
    await openPanelAndSend('Review my profile');

    const reviewText = [
      'REVIEW',
      'Working: Your reels get strong watch time in the first 3 seconds',
      'Not working: Your bio has no clear call to action',
      'Next 1: Add a link-in-bio call to action today',
      'Next 2: Post one reel this week using the saffron hook template',
      'Next 3: Reply to your last 5 comments to lift engagement',
    ].join('\n');

    act(() => {
      lastHandlers().onToken?.({ text: reviewText });
      lastHandlers().onDone?.({ finish_reason: 'stop' });
    });

    const card = await screen.findByTestId('meera-review-card');
    expect(card).toHaveTextContent('Your bio has no clear call to action');
  });
});
