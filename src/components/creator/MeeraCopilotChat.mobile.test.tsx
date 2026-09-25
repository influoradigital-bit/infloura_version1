/**
 * Meera's chat on a phone (live screenshots, 2026-09-24):
 * - below `sm` it opens full screen and locks the page behind it (the in-page box left ~300px for
 *   messages inside a page that also scrolled); the lock is released when the chat closes;
 * - her light markdown shows cleanly: **bold** is bold, never raw asterisks, `---` lines vanish;
 * - a script with bold labels still becomes the card.
 *
 * Photo check in Meera's chat, SPEC 3b "Composer on phones" (2026-09-26):
 * - the full-screen height is the VISIBLE viewport (`--chat-vh`, from visualViewport) so the iOS
 *   keyboard never hides the composer; the trust line and quick actions step aside while it is up;
 * - the composer is 16px on phones (iOS zooms the page on focus below that), grows to max-h-32 and
 *   then scrolls, and clears the home indicator (safe-area bottom padding);
 * - the page scroll lock follows the media query's change events (rotation, resize), not only
 *   the mount.
 *
 * Run: npx vitest run src/components/creator/MeeraCopilotChat.mobile.test.tsx
 */
import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
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


function phone(matches: boolean) {
  vi.spyOn(window, 'matchMedia').mockImplementation(
    (query: string) =>
      ({
        matches: query.includes('max-width: 639px') ? matches : false,
        media: query,
        onchange: null,
        addListener: vi.fn(),
        removeListener: vi.fn(),
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
        dispatchEvent: vi.fn(),
      }) as MediaQueryList,
  );
}

describe('MeeraCopilotChat on a phone', () => {
  it('opens full screen below sm and keeps its in-page size from sm up', async () => {
    phone(true);
    await openPanelAndSend('hi');
    const root = screen.getByPlaceholderText(/Ask Meera/i).closest('.fixed');
    expect(root).not.toBeNull();
    expect(root!.className).toContain('inset-0');
    // The visible viewport's height (iOS keyboard), falling back to 100dvh.
    expect(root!.className).toContain('h-[var(--chat-vh,100dvh)]');
    expect(root!.className).toContain('sm:static');
    expect(root!.className).toContain('sm:h-[32rem]');
  });

  it('locks the page behind it on a phone and releases the lock when it closes', async () => {
    phone(true);
    document.body.style.overflow = '';
    startSessionMock.mockResolvedValue({ conversationId: 'conv_1' });
    getHistoryMock.mockResolvedValue([]);
    const { unmount } = render(
      <MemoryRouter>
        <MeeraCopilotChat firstName="Asha" language="en-IN" onClose={vi.fn()} onConsentRequired={vi.fn()} />
      </MemoryRouter>,
    );
    await waitFor(() => expect(startSessionMock).toHaveBeenCalled());
    expect(document.body.style.overflow).toBe('hidden');
    unmount();
    expect(document.body.style.overflow).toBe('');
  });

  it('does not lock the page on a tablet or desktop', async () => {
    phone(false);
    document.body.style.overflow = '';
    await openPanelAndSend('hi');
    expect(document.body.style.overflow).toBe('');
  });
});

describe("Meera's light markdown in the chat", () => {
  it('shows **bold** as bold text and drops --- divider lines, never raw asterisks', async () => {
    phone(false);
    await openPanelAndSend('what is my best time?');
    act(() => {
      lastHandlers().onToken?.({ text: 'Your best time is **weekend afternoon**.\n---\nWant a script for it?' });
    });
    act(() => {
      lastHandlers().onDone?.({ finish_reason: 'stop' });
    });
    const bold = await screen.findByText('weekend afternoon');
    expect(bold.tagName).toBe('STRONG');
    const bubble = bold.parentElement!;
    expect(bubble.textContent).not.toContain('**');
    expect(bubble.textContent).not.toContain('---');
    expect(bubble.textContent).toContain('Want a script for it?');
  });

  it('a script whose labels came back bold still becomes the card', async () => {
    phone(false);
    await openPanelAndSend();
    const bolded = SCRIPT_TEXT.replace('Idea:', '**Idea:**').replace('Caption:', '**Caption:**');
    act(() => {
      lastHandlers().onToken?.({ text: `${bolded}\n---` });
    });
    act(() => {
      lastHandlers().onDone?.({ finish_reason: 'stop' });
    });
    expect(await screen.findByTestId('script-card-copy')).toBeInTheDocument();
  });
});

/** A matchMedia whose phone query can be flipped later through its 'change' listeners. */
function switchablePhone(initial: boolean) {
  const listeners = new Set<(e: MediaQueryListEvent) => void>();
  let matches = initial;
  vi.spyOn(window, 'matchMedia').mockImplementation(
    (query: string) =>
      ({
        get matches() {
          return query.includes('max-width: 639px') ? matches : false;
        },
        media: query,
        onchange: null,
        addListener: vi.fn(),
        removeListener: vi.fn(),
        addEventListener: (_type: string, cb: (e: MediaQueryListEvent) => void) => {
          if (query.includes('max-width: 639px')) listeners.add(cb);
        },
        removeEventListener: (_type: string, cb: (e: MediaQueryListEvent) => void) => {
          listeners.delete(cb);
        },
        dispatchEvent: vi.fn(),
      }) as unknown as MediaQueryList,
  );
  return {
    listeners,
    set(next: boolean) {
      matches = next;
      act(() => {
        for (const cb of [...listeners]) cb({ matches: next } as MediaQueryListEvent);
      });
    },
  };
}

function renderChat() {
  startSessionMock.mockResolvedValue({ conversationId: 'conv_1' });
  getHistoryMock.mockResolvedValue([]);
  return render(
    <MemoryRouter>
      <MeeraCopilotChat firstName="Asha" language="en-IN" onClose={vi.fn()} onConsentRequired={vi.fn()} />
    </MemoryRouter>,
  );
}

describe('the composer on a phone', () => {
  it('is 16px below sm (no iOS focus zoom), 14px from sm, and stops growing at max-h-32', async () => {
    phone(true);
    renderChat();
    const textbox = await screen.findByPlaceholderText(/Ask Meera/i);
    expect(textbox).toHaveClass('text-base', 'sm:text-sm', 'max-h-32', 'overflow-y-auto');
    // The bare 14px class that overrode the primitive's text-base is gone.
    expect(textbox).not.toHaveClass('text-sm');
  });

  it('clears the home indicator with safe-area bottom padding', async () => {
    phone(true);
    renderChat();
    const textbox = await screen.findByPlaceholderText(/Ask Meera/i);
    const composer = textbox.closest('.border-t');
    expect(composer).not.toBeNull();
    expect(composer!.className).toContain('pb-[max(0.75rem,env(safe-area-inset-bottom))]');
  });
});

describe('the page scroll lock follows the media query', () => {
  it('locks when the window turns phone-sized and unlocks when it grows back', async () => {
    const mq = switchablePhone(false);
    document.body.style.overflow = '';
    const { unmount } = renderChat();
    await waitFor(() => expect(startSessionMock).toHaveBeenCalled());
    expect(document.body.style.overflow).toBe('');
    expect(mq.listeners.size).toBeGreaterThan(0);

    mq.set(true);
    expect(document.body.style.overflow).toBe('hidden');

    mq.set(false);
    expect(document.body.style.overflow).toBe('');

    mq.set(true);
    unmount();
    expect(document.body.style.overflow).toBe('');
    expect(mq.listeners.size).toBe(0);
  });
});

describe('the phone keyboard (visualViewport)', () => {
  const original = Object.getOwnPropertyDescriptor(window, 'visualViewport');
  let vv: EventTarget & { height: number };

  beforeEach(() => {
    vv = Object.assign(new EventTarget(), { height: window.innerHeight });
    Object.defineProperty(window, 'visualViewport', { configurable: true, value: vv });
  });
  afterEach(() => {
    if (original) Object.defineProperty(window, 'visualViewport', original);
    else delete (window as { visualViewport?: unknown }).visualViewport;
  });

  it('sizes the chat to the visible viewport and hides the trust line and quick actions while the keyboard is up', async () => {
    phone(true);
    renderChat();
    const textbox = await screen.findByPlaceholderText(/Ask Meera/i);
    const root = textbox.closest('.fixed') as HTMLElement;
    expect(root.style.getPropertyValue('--chat-vh')).toBe(`${window.innerHeight}px`);
    expect(screen.getByText(/reads only your own Influora data/i)).toBeInTheDocument();

    const keyboardHeight = 320;
    vv.height = window.innerHeight - keyboardHeight;
    act(() => {
      vv.dispatchEvent(new Event('resize'));
    });
    await waitFor(() => expect(root.style.getPropertyValue('--chat-vh')).toBe(`${window.innerHeight - keyboardHeight}px`));
    expect(screen.queryByText(/reads only your own Influora data/i)).toBeNull();

    vv.height = window.innerHeight;
    act(() => {
      vv.dispatchEvent(new Event('resize'));
    });
    await waitFor(() => expect(screen.getByText(/reads only your own Influora data/i)).toBeInTheDocument());
  });

  it('sees the keyboard on Android too, where the window shrinks with it (resizes-content)', async () => {
    const innerHeight = Object.getOwnPropertyDescriptor(window, 'innerHeight');
    const fullHeight = window.innerHeight;
    try {
      phone(true);
      renderChat();
      await screen.findByPlaceholderText(/Ask Meera/i);
      expect(screen.getByText(/reads only your own Influora data/i)).toBeInTheDocument();

      // Android Chrome with interactive-widget=resizes-content: BOTH heights drop by the keyboard.
      Object.defineProperty(window, 'innerHeight', { configurable: true, value: fullHeight - 300 });
      vv.height = fullHeight - 300;
      act(() => {
        vv.dispatchEvent(new Event('resize'));
      });
      await waitFor(() => expect(screen.queryByText(/reads only your own Influora data/i)).toBeNull());
    } finally {
      if (innerHeight) Object.defineProperty(window, 'innerHeight', innerHeight);
    }
  });

  it('follows the iOS pan: the chat top moves down by visualViewport.offsetTop', async () => {
    phone(true);
    renderChat();
    const textbox = await screen.findByPlaceholderText(/Ask Meera/i);
    const root = textbox.closest('.fixed') as HTMLElement;
    expect(root.className).toContain('top-[var(--chat-vv-top,0px)]');
    expect(root.style.getPropertyValue('--chat-vv-top')).toBe('0px');

    Object.assign(vv, { offsetTop: 84 });
    act(() => {
      vv.dispatchEvent(new Event('scroll'));
    });
    await waitFor(() => expect(root.style.getPropertyValue('--chat-vv-top')).toBe('84px'));
  });

  it('keeps the trust line on a tablet even when the visible viewport is short', async () => {
    phone(false);
    vv.height = window.innerHeight - 400;
    renderChat();
    await screen.findByPlaceholderText(/Ask Meera/i);
    expect(screen.getByText(/reads only your own Influora data/i)).toBeInTheDocument();
  });
});
