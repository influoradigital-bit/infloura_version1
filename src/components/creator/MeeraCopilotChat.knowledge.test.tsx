/**
 * MeeraCopilotChat — the LOCAL `get_creator_knowledge` tool (Meera reading Influora's own notes).
 *
 * influora-ai runs this tool in its own loop and never forwards it to Spring, so it is
 * deliberately NOT in `CREATOR_TOOL_NAMES` (that list is held to influora-ai's Spring-backed tuple
 * by meera-api.creator-tools-in-sync.test.ts). Before this, `isCreatorToolName` dropped it: the
 * creator saw nothing while Meera was looking something up. These pin what the creator sees now:
 *   1. one friendly work-trail step that names the topic ("Checking Influora's notes on audio"),
 *   2. NO spinner row, NO result card and NO dump of the returned notes or their JSON,
 *   3. an error or unknown topic still shows a plain step, never the raw error code or topic key,
 *      and never crashes the chat.
 *
 * `@/lib/meera-api` is mocked with `importOriginal` so the REAL `isCreatorToolName` /
 * `isCreatorLocalToolName` guards run, same reasoning as MeeraCopilotChat.toolcards.test.tsx.
 */
import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { MeeraCopilotChat } from './MeeraCopilotChat';
import { MeeraWorkTrail } from './meera/MeeraWorkTrail';
import type { MeeraStreamHandlers } from '@/hooks/useMeeraStream';
import { CREATOR_KNOWLEDGE_TOPICS, CREATOR_LOCAL_TOOL_NAMES, CREATOR_TOOL_NAMES } from '@/lib/meera-api';

const { openMock, closeMock, startSessionMock, getHistoryMock, sendTurnMock } = vi.hoisted(() => ({
  openMock: vi.fn(),
  closeMock: vi.fn(),
  startSessionMock: vi.fn(),
  getHistoryMock: vi.fn(),
  sendTurnMock: vi.fn(),
}));

// vitest.config.ts pins VITE_API_MODE=mock, and mock mode opens no stream at all.
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

/** A distinctive stand-in for the notes influora-ai returns; it must never reach the screen. */
const KNOWLEDGE_TEXT =
  'AUDIO NOTES FOR MEERA ONLY: clip the lav mic two fingers below the collarbone, switch the fan off.';

async function openPanelAndSend() {
  const user = userEvent.setup();
  startSessionMock.mockResolvedValue({ conversationId: 'conv_knowledge' });
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
  await user.type(textbox, 'My audio sounds echoey, what do I do?');
  await user.keyboard('{Enter}');
  await waitFor(() => expect(openMock).toHaveBeenCalled());
  return user;
}

afterEach(() => {
  vi.clearAllMocks();
  vi.restoreAllMocks();
});

describe('get_creator_knowledge is a local tool, kept apart from the Spring-backed list', () => {
  it('is not in CREATOR_TOOL_NAMES (the list synced with influora-ai) but is a known local tool', () => {
    expect(CREATOR_TOOL_NAMES as readonly string[]).not.toContain('get_creator_knowledge');
    expect(CREATOR_LOCAL_TOOL_NAMES).toEqual(['get_creator_knowledge']);
    expect(CREATOR_KNOWLEDGE_TOPICS).toEqual(['audio', 'moving_between_spots', 'delivery_examples']);
  });
});

describe('MeeraWorkTrail — get_creator_knowledge step labels', () => {
  it.each([
    ['audio', "Checking Influora's notes on audio…"],
    ['moving_between_spots', "Checking Influora's notes on moving between spots…"],
    ['delivery_examples', "Checking Influora's notes on delivery examples…"],
  ])('names the topic while running (%s)', (topic, label) => {
    render(
      <MeeraWorkTrail
        steps={[{ id: 's1', name: 'get_creator_knowledge', status: 'pending', topic }]}
        done={false}
        language="en-IN"
      />,
    );
    expect(screen.getByText(label)).toBeInTheDocument();
  });

  it('says "Checked" once the lookup is done and "Couldn\'t open" when it fails', () => {
    render(
      <MeeraWorkTrail
        steps={[
          { id: 's1', name: 'get_creator_knowledge', status: 'ok', topic: 'audio' },
          { id: 's2', name: 'get_creator_knowledge', status: 'error', topic: 'delivery_examples' },
        ]}
        done={false}
        language="en-IN"
      />,
    );
    expect(screen.getByText("Checked Influora's notes on audio")).toBeInTheDocument();
    expect(screen.getByText("Couldn't open Influora's notes on delivery examples")).toBeInTheDocument();
  });

  it('never shows a raw topic key: a missing or unknown topic gets the plain label', () => {
    render(
      <MeeraWorkTrail
        steps={[
          { id: 's1', name: 'get_creator_knowledge', status: 'pending' },
          { id: 's2', name: 'get_creator_knowledge', status: 'ok', topic: 'lighting_secrets' },
        ]}
        done={false}
        language="en-IN"
      />,
    );
    expect(screen.getByText("Checking Influora's notes…")).toBeInTheDocument();
    expect(screen.getByText("Checked Influora's notes")).toBeInTheDocument();
    expect(screen.queryByText(/lighting_secrets/)).toBeNull();
  });

  it('speaks Hindi to a Hindi creator', () => {
    render(
      <MeeraWorkTrail
        steps={[{ id: 's1', name: 'get_creator_knowledge', status: 'pending', topic: 'audio' }]}
        done={false}
        language="hi-IN"
      />,
    );
    expect(screen.getByText('Influora के ऑडियो वाले नोट्स देखे जा रहे हैं…')).toBeInTheDocument();
  });
});

describe('MeeraCopilotChat — get_creator_knowledge events', () => {
  it('shows one friendly trail step and no card, spinner row or dump of the notes', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const user = await openPanelAndSend();

    act(() => {
      lastHandlers().onToolStart?.({ name: 'get_creator_knowledge', input: { topic: 'audio' } });
    });
    expect(await screen.findByText("Checking Influora's notes on audio…")).toBeInTheDocument();
    // The trail step is the only sign of it: no separate spinner row under the bubble.
    expect(screen.queryByTestId('creator-tool-pending')).toBeNull();

    act(() => {
      lastHandlers().onToolResult?.({
        name: 'get_creator_knowledge',
        status: 'ok',
        data: { topic: 'audio', knowledge: KNOWLEDGE_TEXT },
      });
      lastHandlers().onToken?.({ text: 'Move the mic closer and turn the fan off while you record.' });
    });

    expect(await screen.findByText("Checked Influora's notes on audio")).toBeInTheDocument();
    expect(screen.queryByText("Checking Influora's notes on audio…")).toBeNull();
    const steps = screen.getAllByTestId('work-trail-step');
    expect(steps).toHaveLength(1);
    expect(steps[0]).toHaveAttribute('data-tool-status', 'ok');

    act(() => {
      lastHandlers().onDone?.({ finish_reason: 'stop' });
    });
    // Expanding the collapsed trail still shows only the friendly line.
    await user.click(await screen.findByRole('button', { name: /Meera did 1 thing/i }));
    expect(await screen.findByText("Checked Influora's notes on audio")).toBeInTheDocument();

    const pageText = document.body.textContent ?? '';
    expect(pageText).not.toContain(KNOWLEDGE_TEXT);
    expect(pageText).not.toContain('AUDIO NOTES');
    expect(pageText).not.toContain('"knowledge"');
    expect(pageText).not.toContain('get_creator_knowledge');
    expect(screen.queryByTestId('creator-tool-pending')).toBeNull();
    expect(warn).not.toHaveBeenCalledWith('[MeeraCopilotChat] ignoring unknown tool name:', 'get_creator_knowledge');
  });

  it('shows a plain failed step, never the raw error or topic list, on an unknown_topic error', async () => {
    await openPanelAndSend();

    act(() => {
      lastHandlers().onToolStart?.({ name: 'get_creator_knowledge', input: { topic: 'lighting_secrets' } });
      lastHandlers().onToolResult?.({
        name: 'get_creator_knowledge',
        status: 'error',
        data: { error: 'unknown_topic', topics: ['audio', 'moving_between_spots', 'delivery_examples'] },
      });
      lastHandlers().onToken?.({ text: 'Here is what I would do.' });
    });

    expect(await screen.findByText("Couldn't open Influora's notes")).toBeInTheDocument();
    expect(screen.getByText('Here is what I would do.')).toBeInTheDocument();
    const pageText = document.body.textContent ?? '';
    expect(pageText).not.toContain('unknown_topic');
    expect(pageText).not.toContain('lighting_secrets');
    expect(pageText).not.toContain('moving_between_spots');
    // An error card is a Spring-tool thing; the local tool gets none.
    expect(screen.queryByTestId('creator-tool-pending')).toBeNull();
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('keeps the topic from tool_start when the result arrives, next to a Spring tool that still gets its card', async () => {
    await openPanelAndSend();

    act(() => {
      lastHandlers().onToolStart?.({ name: 'get_creator_knowledge', input: { topic: 'moving_between_spots' } });
      lastHandlers().onToolStart?.({ name: 'get_my_deals', input: {} });
    });
    // The Spring tool keeps its own spinner row; the local one does not add a second.
    expect(await screen.findAllByTestId('creator-tool-pending')).toHaveLength(1);

    act(() => {
      lastHandlers().onToolResult?.({
        name: 'get_creator_knowledge',
        status: 'ok',
        data: { knowledge: KNOWLEDGE_TEXT },
      });
      lastHandlers().onToolResult?.({
        name: 'get_my_deals',
        status: 'ok',
        data: { deals: [], active_count: 0, completed_count: 0 },
      });
      lastHandlers().onDone?.({ finish_reason: 'stop' });
    });

    expect(await screen.findByRole('button', { name: /Meera did 2 things/i })).toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole('button', { name: /Meera did 2 things/i }));
    expect(await screen.findByText("Checked Influora's notes on moving between spots")).toBeInTheDocument();
    expect(screen.getByText('Read your deals')).toBeInTheDocument();
    expect(document.body.textContent ?? '').not.toContain(KNOWLEDGE_TEXT);
  });
});
