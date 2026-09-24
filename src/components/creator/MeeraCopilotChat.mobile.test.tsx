/**
 * Meera's chat on a phone (live screenshots, 2026-09-24):
 * - below `sm` it opens full screen and locks the page behind it (the in-page box left ~300px for
 *   messages inside a page that also scrolled); the lock is released when the chat closes;
 * - her light markdown shows cleanly: **bold** is bold, never raw asterisks, `---` lines vanish;
 * - a script with bold labels still becomes the card.
 *
 * Run: npx vitest run src/components/creator/MeeraCopilotChat.mobile.test.tsx
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
    expect(root!.className).toContain('h-[100dvh]');
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
