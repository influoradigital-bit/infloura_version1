/**
 * Long chats (photo check in Meera's chat, 2026-09-26 build SPEC section 3).
 *
 * - only the newest 50 rows are in the DOM; "Show earlier messages" reveals 50 more from memory,
 *   then pages older ones from the server (`getHistoryBefore`), keeping the reader's place;
 * - a streamed token re-renders ONLY the streaming row, never the other rows of a 200-row thread
 *   (a React Profiler inside every row, plus a spy on each row's text render);
 * - the list follows new content only when the reader is at the bottom; scrolled up, a token
 *   never scrolls and a "New messages" pill appears instead; an in-place edit ("Show as text")
 *   never scrolls at all.
 *
 * jsdom has no layout, so the scroll container's scrollHeight/scrollTop/clientHeight are stubbed.
 *
 * Run: npx vitest run src/components/creator/MeeraCopilotChat.long-chat.test.tsx
 */
import * as React from 'react';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { MeeraCopilotChat } from './MeeraCopilotChat';
import type { MeeraStreamHandlers } from '@/hooks/useMeeraStream';

const {
  openMock,
  closeMock,
  startSessionMock,
  getHistoryMock,
  getHistoryBeforeMock,
  sendTurnMock,
  renderTextSpy,
  trailRenders,
} = vi.hoisted(() => ({
  openMock: vi.fn(),
  closeMock: vi.fn(),
  startSessionMock: vi.fn(),
  getHistoryMock: vi.fn(),
  getHistoryBeforeMock: vi.fn(),
  sendTurnMock: vi.fn(),
  renderTextSpy: vi.fn(),
  trailRenders: { count: 0 },
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
    getHistoryBefore: (...args: unknown[]) => getHistoryBeforeMock(...args),
    sendTurn: (...args: unknown[]) => sendTurnMock(...args),
    checkFrame: vi.fn(),
  },
}));

// Every Meera row renders its text through this; the spy records WHICH text rendered.
vi.mock('@/lib/meera-text', async (importOriginal) => {
  const mod = await importOriginal<typeof import('@/lib/meera-text')>();
  return {
    ...mod,
    renderMeeraText: (text: string) => {
      renderTextSpy(text);
      return mod.renderMeeraText(text);
    },
  };
});

// A React Profiler INSIDE every Meera row (the work trail is its first child). A memoised row
// that bails out never reaches it, so each onRender is one row that really rendered.
vi.mock('@/components/creator/meera/MeeraWorkTrail', async (importOriginal) => {
  const mod = await importOriginal<typeof import('@/components/creator/meera/MeeraWorkTrail')>();
  const ReactMod = await import('react');
  return {
    ...mod,
    MeeraWorkTrail: (props: React.ComponentProps<typeof mod.MeeraWorkTrail>) =>
      ReactMod.createElement(
        ReactMod.Profiler,
        {
          id: 'meera-row',
          onRender: () => {
            trailRenders.count += 1;
          },
        },
        ReactMod.createElement(mod.MeeraWorkTrail, props),
      ),
  };
});

vi.mock('@/hooks/useMeeraStream', () => ({
  useMeeraStream: () => ({ status: 'idle', open: openMock, close: closeMock, lastError: null }),
}));

vi.mock('@/hooks/useVoiceOutput', () => ({
  useVoiceOutput: () => ({ supported: false, enabled: false, setEnabled: vi.fn(), speak: vi.fn(), stop: vi.fn() }),
}));

vi.mock('@/hooks/useVoiceInput', () => ({
  useVoiceInput: () => ({ supported: false, isListening: false, start: vi.fn(), stop: vi.fn() }),
}));

const SCRIPT_TEXT = [
  'Idea: 3 saffron mistakes to avoid',
  'Plan: for new saffron buyers; curiosity; grow followers; 30s, vertical 9:16; problem-agitate-solve; curiosity-gap hook',
  'Action: hold up the saffron box and speak to camera',
  'Success looks like: reel gets watched to the end and shared to a friend',
  'Script:',
  '0-10s. Shot: Close-up on your face - hold up the saffron box. Say: "Is your saffron even real?". On screen: REAL vs FAKE',
  '10-20s. Shot: Overhead on your hands - do the warm-water color test. Say: "Watch what happens in water". On screen: THE WATER TEST',
  '20-30s. Shot: Close-up on the certificate - hold it next to the box. Say: "This is what real Kashmiri saffron looks like". On screen: GI CERTIFIED',
  'Caption: Would you have spotted the fake one? Link in bio for real Kashmiri saffron.',
  'Before you shoot: 1) Charge your phone to 100% 2) Wipe the counter clean 3) Keep the certificate within reach',
  'Why this works: Problem-agitate-solve structure, curiosity-gap hook template',
].join('\n');

/** `count` stored rows, oldest first, alternating creator/Meera, ids `${prefix}0000`... */
function historyRows(count: number, prefix = 'h', start = 0) {
  return Array.from({ length: count }, (_, i) => {
    const n = start + i;
    const id = `${prefix}${String(n).padStart(4, '0')}`;
    return n % 2 === 0
      ? { id, role: 'USER' as const, content: `creator line ${prefix}${n}` }
      : { id, role: 'ASSISTANT' as const, content: `meera line ${prefix}${n}` };
  });
}

const ROW_PX = 40;

/**
 * Layout for the scroll container: each rendered row is ROW_PX tall, the viewport is 400px, and
 * scrollTop is a plain settable number. `scrollTo` records its calls.
 */
function stubLayout() {
  const el = screen.getByTestId('chat-scroll');
  let top = 0;
  Object.defineProperty(el, 'scrollHeight', {
    configurable: true,
    get: () => el.querySelectorAll('[data-testid="chat-turn"]').length * ROW_PX,
  });
  Object.defineProperty(el, 'clientHeight', { configurable: true, get: () => 400 });
  Object.defineProperty(el, 'scrollTop', {
    configurable: true,
    get: () => top,
    set: (v: number) => {
      top = v;
    },
  });
  const scrollTo = vi.fn((opts: ScrollToOptions) => {
    top = Math.max(0, (opts.top ?? 0) - 400);
  });
  (el as unknown as { scrollTo: typeof scrollTo }).scrollTo = scrollTo;
  return {
    el,
    scrollTo,
    /** The reader scrolls to `px` from the top. */
    scrollReaderTo(px: number) {
      top = px;
      fireEvent.scroll(el);
    },
    get top() {
      return top;
    },
  };
}

function lastHandlers(): MeeraStreamHandlers {
  const call = openMock.mock.calls[openMock.mock.calls.length - 1];
  return call[2] as MeeraStreamHandlers;
}

async function openWithHistory(rows: ReturnType<typeof historyRows>) {
  startSessionMock.mockResolvedValue({ conversationId: 'conv_1' });
  getHistoryMock.mockResolvedValue(rows);
  render(
    <MemoryRouter>
      <MeeraCopilotChat firstName="Asha" language="en-IN" onClose={vi.fn()} onConsentRequired={vi.fn()} />
    </MemoryRouter>,
  );
  await waitFor(() => expect(screen.getAllByTestId('chat-turn').length).toBeGreaterThan(0));
}

async function sendAndOpenStream(text: string) {
  const user = userEvent.setup();
  sendTurnMock.mockResolvedValueOnce({
    messageId: 'm1',
    streamUrl: 'http://x/chat',
    streamToken: 'tok',
    workspaceId: 'ws_1',
    reply: null,
  });
  const textbox = screen.getByPlaceholderText(/Ask Meera/i);
  await user.click(textbox);
  fireEvent.change(textbox, { target: { value: text } });
  await user.keyboard('{Enter}');
  await waitFor(() => expect(openMock).toHaveBeenCalled());
  return user;
}

function ids(): string[] {
  return screen.getAllByTestId('chat-turn').map((row) => row.getAttribute('data-message-id') ?? '');
}

beforeEach(() => {
  trailRenders.count = 0;
});

afterEach(() => {
  vi.clearAllMocks();
  vi.restoreAllMocks();
});

describe('the DOM window', () => {
  it('renders only the newest 50 of 300 rows, plus "Show earlier messages"', async () => {
    await openWithHistory(historyRows(300));
    expect(screen.getAllByTestId('chat-turn')).toHaveLength(50);
    expect(ids()[0]).toBe('h0250');
    expect(ids()[49]).toBe('h0299');
    expect(screen.getByTestId('show-earlier')).toHaveTextContent('Show earlier messages');
  });

  it('"Show earlier" reveals 50 more from memory first, then pages older ones from the server', async () => {
    await openWithHistory(historyRows(100, 'h', 100));
    const layout = stubLayout();
    getHistoryBeforeMock.mockResolvedValueOnce(historyRows(50, 'h', 50)).mockResolvedValueOnce(historyRows(20, 'h', 30));

    // From memory: no server call.
    fireEvent.click(screen.getByTestId('show-earlier'));
    await waitFor(() => expect(screen.getAllByTestId('chat-turn')).toHaveLength(100));
    expect(getHistoryBeforeMock).not.toHaveBeenCalled();
    expect(ids()[0]).toBe('h0100');

    // Memory is used up: the oldest row's id is the cursor.
    fireEvent.click(screen.getByTestId('show-earlier'));
    await waitFor(() => expect(screen.getAllByTestId('chat-turn')).toHaveLength(150));
    expect(getHistoryBeforeMock).toHaveBeenCalledWith('conv_1', 'h0100', 'creator');
    expect(ids()[0]).toBe('h0050');
    // A full page (50) means there may be more.
    expect(screen.getByTestId('show-earlier')).toBeInTheDocument();

    fireEvent.click(screen.getByTestId('show-earlier'));
    await waitFor(() => expect(screen.getAllByTestId('chat-turn')).toHaveLength(170));
    expect(getHistoryBeforeMock).toHaveBeenLastCalledWith('conv_1', 'h0050', 'creator');
    // A short page is the start of the conversation.
    expect(screen.queryByTestId('show-earlier')).toBeNull();
    expect(layout.scrollTo).not.toHaveBeenCalled();
  });

  it('an older Spring that ignores ?before= (newest rows again): no duplicates, and paging stops', async () => {
    await openWithHistory(historyRows(100, 'h', 0));
    stubLayout();
    // What a Spring without ?before= answers: the newest page, again.
    getHistoryBeforeMock.mockResolvedValue(historyRows(100, 'h', 0));

    fireEvent.click(screen.getByTestId('show-earlier')); // from memory
    await waitFor(() => expect(screen.getAllByTestId('chat-turn')).toHaveLength(100));
    fireEvent.click(screen.getByTestId('show-earlier')); // to the server
    await waitFor(() => expect(getHistoryBeforeMock).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(screen.queryByTestId('show-earlier')).toBeNull());
    expect(screen.getAllByTestId('chat-turn')).toHaveLength(100);
    expect(new Set(ids()).size).toBe(100);
  });

  it('keeps the reader\'s place when older rows are added above them', async () => {
    await openWithHistory(historyRows(100));
    const layout = stubLayout();
    layout.scrollReaderTo(120);
    fireEvent.click(screen.getByTestId('show-earlier'));
    await waitFor(() => expect(screen.getAllByTestId('chat-turn')).toHaveLength(100));
    // 50 rows of 40px went in above: the same row stays under the reader's eye.
    expect(layout.top).toBe(120 + 50 * ROW_PX);
    expect(layout.scrollTo).not.toHaveBeenCalled();
  });
});

describe('streaming into a long chat', () => {
  it('re-renders only the streaming row: 30 tokens with 200 rows', async () => {
    await openWithHistory(historyRows(200));
    stubLayout();
    await sendAndOpenStream('one more question');

    renderTextSpy.mockClear();
    trailRenders.count = 0;
    const tokens = Array.from({ length: 30 }, (_, i) => `tok${i} `);
    for (const token of tokens) {
      act(() => {
        lastHandlers().onToken?.({ text: token });
      });
    }
    const streamed = tokens.join('');
    expect(screen.getAllByTestId('chat-turn').at(-1)).toHaveTextContent(streamed.trim());

    // Every text render was the streaming row's own (growing) text, never an older row's.
    const rendered = renderTextSpy.mock.calls.map((c) => c[0] as string);
    expect(rendered.length).toBeGreaterThanOrEqual(30);
    expect(rendered.filter((t) => !streamed.startsWith(t))).toEqual([]);
    // Profiler inside every Meera row: one commit per token for the one streaming row (the
    // first token mounts it), never ~25 more for the other Meera rows on screen.
    expect(trailRenders.count).toBeGreaterThanOrEqual(30);
    expect(trailRenders.count).toBeLessThanOrEqual(31);
  });
});

describe('scroll that respects the reader', () => {
  it('follows a token with an instant scroll while the reader is at the bottom', async () => {
    await openWithHistory(historyRows(20));
    const layout = stubLayout();
    await sendAndOpenStream('question');
    layout.scrollReaderTo(layout.el.scrollHeight - 400);
    layout.scrollTo.mockClear();

    act(() => {
      lastHandlers().onToken?.({ text: 'Hello' });
    });
    expect(layout.scrollTo).toHaveBeenCalledWith(expect.objectContaining({ behavior: 'auto' }));
    expect(screen.queryByTestId('new-messages-pill')).toBeNull();
  });

  it('scrolled up, a token never scrolls and the "New messages" pill appears; the pill jumps down', async () => {
    await openWithHistory(historyRows(20));
    const layout = stubLayout();
    await sendAndOpenStream('question');
    act(() => {
      lastHandlers().onToken?.({ text: 'Hel' });
    });
    layout.scrollReaderTo(0);
    layout.scrollTo.mockClear();

    for (const text of ['lo', ' there', ', here is more']) {
      act(() => {
        lastHandlers().onToken?.({ text });
      });
    }
    expect(layout.scrollTo).not.toHaveBeenCalled();
    const pill = await screen.findByTestId('new-messages-pill');
    expect(pill).toHaveTextContent('New messages');
    expect(pill.parentElement).toHaveAttribute('aria-live', 'polite');

    fireEvent.click(pill);
    expect(layout.scrollTo).toHaveBeenCalledTimes(1);
    expect(screen.queryByTestId('new-messages-pill')).toBeNull();
  });

  it('a send always jumps to the bottom, even when scrolled up', async () => {
    await openWithHistory(historyRows(20));
    const layout = stubLayout();
    layout.scrollReaderTo(0);
    layout.scrollTo.mockClear();
    await sendAndOpenStream('question');
    expect(layout.scrollTo).toHaveBeenCalled();
    expect(screen.queryByTestId('new-messages-pill')).toBeNull();
  });

  it('"Show as text" on a card never scrolls, at the bottom or not', async () => {
    await openWithHistory(historyRows(20));
    const layout = stubLayout();
    await sendAndOpenStream('write me a script');
    act(() => {
      lastHandlers().onToken?.({ text: SCRIPT_TEXT });
    });
    act(() => {
      lastHandlers().onDone?.({ finish_reason: 'stop' });
    });
    const toggle = await screen.findByTestId('result-card-text-toggle');

    // At the bottom.
    layout.scrollReaderTo(layout.el.scrollHeight - 400);
    layout.scrollTo.mockClear();
    fireEvent.click(toggle);
    expect(await screen.findByText(/Show as card/)).toBeInTheDocument();
    expect(layout.scrollTo).not.toHaveBeenCalled();

    // Scrolled up: no scroll and no pill either (nothing new arrived).
    layout.scrollReaderTo(0);
    fireEvent.click(screen.getByTestId('result-card-text-toggle'));
    expect(await screen.findByText(/Show as text/)).toBeInTheDocument();
    expect(layout.scrollTo).not.toHaveBeenCalled();
    expect(screen.queryByTestId('new-messages-pill')).toBeNull();
  });
});
