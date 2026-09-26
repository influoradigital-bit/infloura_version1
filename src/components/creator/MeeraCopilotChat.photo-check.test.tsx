/**
 * Photo check inside Meera's chat (2026-09-26 build SPEC, sections 1 and 3b; Ash 5, 8, 13, 16).
 *
 * - "Check my set-up" on a script beat opens the camera on THAT shot, with every beat as a shot;
 * - a capture sends checkFrame the conversation, an idempotency key and the beat's shot_context;
 * - the result is Meera's message with the coach card, under the server's ids;
 * - a tapped chip re-checks the SAME still with the answers so far, and the older card goes quiet;
 * - a failed check is a local-only bubble; the next turn's history carries the stored summary and
 *   never the greeting, error, refusal or failure bubbles (long-chat bug 4);
 * - a leading "[Photo check" on any other row is neutralised; only the newest check per photo is
 *   replayed; send is off while a check runs; voice mode never reads the summary.
 *
 * The camera sheet itself (getUserMedia, flip, teardown) is Lane D's MeeraCameraSheet.test.tsx;
 * here it is a stand-in that hands back a Blob, so these tests pin the chat's side of the seam.
 * Results come from the committed frame-check fixture through the real parser, never hand-built.
 *
 * Run: npx vitest run src/components/creator/MeeraCopilotChat.photo-check.test.tsx
 */
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { MeeraCopilotChat } from './MeeraCopilotChat';
import type { MeeraStreamHandlers } from '@/hooks/useMeeraStream';
import { ApiError } from '@/lib/api';
import { parseShootCheckFrameBody, type MeeraShootCheckFrameResult } from '@/lib/meera-api';
import { parseMeeraScript } from '@/lib/meera-result-cards';
import { shotFromBeat } from '@/lib/shoot-check/beat-to-shot';
import type { ShootCheckShot } from '@/components/creator/shoot-check/ShootCheckPanel';
import frameBodies from '@/lib/__fixtures__/shoot-check-frame-bodies.json';
import springHistory from '@/lib/__fixtures__/meera-history-with-card.json';

const { openMock, closeMock, startSessionMock, getHistoryMock, sendTurnMock, checkFrameMock, sheetProps, voiceProps } =
  vi.hoisted(() => ({
    openMock: vi.fn(),
    closeMock: vi.fn(),
    startSessionMock: vi.fn(),
    getHistoryMock: vi.fn(),
    sendTurnMock: vi.fn(),
    checkFrameMock: vi.fn(),
    sheetProps: { current: null as null | Record<string, unknown> },
    voiceProps: { current: null as null | Record<string, unknown> },
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
    checkFrame: (...args: unknown[]) => checkFrameMock(...args),
    getHistoryBefore: vi.fn().mockResolvedValue([]),
  },
}));

vi.mock('@/hooks/useMeeraStream', () => ({
  useMeeraStream: () => ({ status: 'idle', open: openMock, close: closeMock, lastError: null }),
}));

vi.mock('@/hooks/useVoiceOutput', () => ({
  useVoiceOutput: () => ({ supported: false, enabled: false, setEnabled: vi.fn(), speak: vi.fn(), stop: vi.fn() }),
}));

vi.mock('@/hooks/useVoiceInput', () => ({
  useVoiceInput: () => ({ supported: false, isListening: false, start: vi.fn(), stop: vi.fn() }),
}));

// Stand-in for Lane D's sheet: shows which shot it opened on and captures on a tap.
vi.mock('@/components/creator/meera/MeeraCameraSheet', () => ({
  MeeraCameraSheet: (props: {
    open: boolean;
    shots: ShootCheckShot[];
    initialShotIndex?: number;
    onCapture: (blob: Blob, shot: ShootCheckShot | null) => void;
    onOpenChange: (open: boolean) => void;
  }) => {
    sheetProps.current = props as unknown as Record<string, unknown>;
    if (!props.open) return null;
    const shot = props.initialShotIndex === undefined ? null : props.shots[props.initialShotIndex] ?? null;
    return (
      <div data-testid="camera-sheet">
        <span data-testid="camera-sheet-shot">{shot ? shot.label : 'any'}</span>
        <button type="button" onClick={() => props.onCapture(new Blob(['jpeg-bytes'], { type: 'image/jpeg' }), shot)}>
          Capture
        </button>
      </div>
    );
  },
}));

// Voice mode stand-in: exposes the lastReply it would show and read.
vi.mock('@/components/creator/meera/MeeraVoiceMode', () => ({
  MeeraVoiceMode: (props: { lastReply?: string }) => {
    voiceProps.current = props as unknown as Record<string, unknown>;
    // An attribute, not text, so it never doubles up with the chat's own bubbles in queries.
    return <span data-testid="voice-last-reply" data-last-reply={props.lastReply ?? ''} />;
  },
}));

const FIXTURES = frameBodies as unknown as Record<string, Record<string, unknown>>;
const BEDROOM: MeeraShootCheckFrameResult = parseShootCheckFrameBody(FIXTURES.bedroom_window_behind_en_a78);
const KITCHEN: MeeraShootCheckFrameResult = parseShootCheckFrameBody(FIXTURES.kitchen_tube_light_hi_no_phone);

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

const SUMMARY_1 = '[Photo check]\nShot: "10-20s · Overhead on your hands"\nPhoto check saw: a bedroom, window behind you.';
const SUMMARY_2 = '[Photo check]\nPhoto check saw: a kitchen under a tube light.';
const GREETING_START = 'Hi Asha!';

function lastHandlers(): MeeraStreamHandlers {
  const call = openMock.mock.calls[openMock.mock.calls.length - 1];
  return call[2] as MeeraStreamHandlers;
}

function lastConversation(): Array<{ role: string; content: string }> {
  const call = openMock.mock.calls[openMock.mock.calls.length - 1];
  return (call[3] as { conversation: Array<{ role: string; content: string }> }).conversation;
}

function streamReply() {
  sendTurnMock.mockResolvedValueOnce({
    messageId: `m${sendTurnMock.mock.calls.length + 1}`,
    streamUrl: 'http://x/chat',
    streamToken: 'tok',
    workspaceId: 'ws_1',
    reply: null,
  });
}

function renderChat(props: Partial<React.ComponentProps<typeof MeeraCopilotChat>> = {}) {
  return render(
    <MemoryRouter>
      <MeeraCopilotChat firstName="Asha" language="en-IN" onClose={vi.fn()} onConsentRequired={vi.fn()} {...props} />
    </MemoryRouter>,
  );
}

async function openChat(props: Partial<React.ComponentProps<typeof MeeraCopilotChat>> = {}) {
  const user = userEvent.setup();
  startSessionMock.mockResolvedValue({ conversationId: 'conv_1' });
  getHistoryMock.mockResolvedValue([]);
  renderChat(props);
  await waitFor(() => expect(getHistoryMock).toHaveBeenCalled());
  await screen.findByText(new RegExp(GREETING_START));
  return user;
}

/** Types `text` (set directly: user-event reads "[" as a key descriptor) and presses Enter. */
async function send(user: ReturnType<typeof userEvent.setup>, text: string) {
  const textbox = screen.getByPlaceholderText(/Ask Meera/i);
  await user.click(textbox);
  fireEvent.change(textbox, { target: { value: text } });
  await user.keyboard('{Enter}');
}

/** Sends a turn and finishes it with `reply` as Meera's streamed text. */
async function sendAndReply(user: ReturnType<typeof userEvent.setup>, text: string, reply: string) {
  streamReply();
  const opens = openMock.mock.calls.length;
  await send(user, text);
  await waitFor(() => expect(openMock.mock.calls.length).toBe(opens + 1));
  act(() => {
    lastHandlers().onToken?.({ text: reply });
  });
  act(() => {
    lastHandlers().onDone?.({ finish_reason: 'stop' });
  });
}

async function captureFromComposer(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByTestId('composer-camera'));
  await user.click(await screen.findByRole('button', { name: 'Capture' }));
}

const originalMediaDevices = Object.getOwnPropertyDescriptor(navigator, 'mediaDevices');
const originalSecure = Object.getOwnPropertyDescriptor(window, 'isSecureContext');

beforeEach(() => {
  // The camera button is shown only where the camera hook could run.
  Object.defineProperty(navigator, 'mediaDevices', { configurable: true, value: { getUserMedia: vi.fn() } });
  Object.defineProperty(window, 'isSecureContext', { configurable: true, value: true });
  sheetProps.current = null;
  voiceProps.current = null;
});

afterEach(() => {
  if (originalMediaDevices) Object.defineProperty(navigator, 'mediaDevices', originalMediaDevices);
  else delete (navigator as { mediaDevices?: unknown }).mediaDevices;
  if (originalSecure) Object.defineProperty(window, 'isSecureContext', originalSecure);
  else delete (window as { isSecureContext?: unknown }).isSecureContext;
  vi.clearAllMocks();
  vi.restoreAllMocks();
});

describe('opening the camera', () => {
  it('"Check my set-up" on a script beat opens the sheet on that shot, with every beat as a shot', async () => {
    const user = await openChat();
    await sendAndReply(user, 'Write me a saffron reel', SCRIPT_TEXT);
    const buttons = await screen.findAllByTestId('script-card-check-shot');
    expect(buttons).toHaveLength(3);

    await user.click(buttons[1]);
    const script = parseMeeraScript(SCRIPT_TEXT)!;
    expect(await screen.findByTestId('camera-sheet-shot')).toHaveTextContent(shotFromBeat(script, 1).label);
    const props = sheetProps.current as unknown as { shots: ShootCheckShot[]; initialShotIndex?: number };
    expect(props.initialShotIndex).toBe(1);
    expect(props.shots.map((s) => s.label)).toEqual([0, 1, 2].map((i) => shotFromBeat(script, i).label));
  });

  it('the composer camera offers the newest script\'s shots with "Any shot" as the default', async () => {
    const user = await openChat();
    await sendAndReply(user, 'Write me a saffron reel', SCRIPT_TEXT);
    await user.click(screen.getByTestId('composer-camera'));
    expect(await screen.findByTestId('camera-sheet-shot')).toHaveTextContent('any');
    const props = sheetProps.current as unknown as { shots: ShootCheckShot[]; initialShotIndex?: number };
    expect(props.shots).toHaveLength(3);
    expect(props.initialShotIndex).toBeUndefined();
  });

  it('openCameraOnMount opens the sheet only once the chat has connected', async () => {
    let resolveSession: (v: { conversationId: string }) => void = () => {};
    startSessionMock.mockReturnValue(new Promise((r) => (resolveSession = r)));
    getHistoryMock.mockResolvedValue([]);
    renderChat({ openCameraOnMount: true });
    await waitFor(() => expect(startSessionMock).toHaveBeenCalled());
    expect(screen.queryByTestId('camera-sheet')).toBeNull();

    await act(async () => {
      resolveSession({ conversationId: 'conv_1' });
    });
    expect(await screen.findByTestId('camera-sheet')).toBeInTheDocument();
    expect(screen.getByTestId('camera-sheet-shot')).toHaveTextContent('any');
  });

  it('the camera button is off while a turn is sending', async () => {
    const user = await openChat();
    streamReply();
    await send(user, 'hello');
    await waitFor(() => expect(openMock).toHaveBeenCalled());
    expect(screen.getByTestId('composer-camera')).toBeDisabled();
    act(() => {
      lastHandlers().onDone?.({ finish_reason: 'stop' });
    });
    await waitFor(() => expect(screen.getByTestId('composer-camera')).toBeEnabled());
  });

  it('there is no camera button where the camera cannot run', async () => {
    Object.defineProperty(window, 'isSecureContext', { configurable: true, value: false });
    await openChat();
    expect(screen.queryByTestId('composer-camera')).toBeNull();
  });
});

describe('a capture', () => {
  it('calls checkFrame with the conversation, an idempotency key and the beat\'s shot_context, then shows the card under the server ids', async () => {
    const user = await openChat();
    await sendAndReply(user, 'Write me a saffron reel', SCRIPT_TEXT);
    checkFrameMock.mockResolvedValueOnce({
      kind: 'ok',
      result: BEDROOM,
      chat: { text: SUMMARY_1, messageId: 'srv_a1', userMessageId: 'srv_u1' },
    });

    await user.click((await screen.findAllByTestId('script-card-check-shot'))[1]);
    await user.click(await screen.findByRole('button', { name: 'Capture' }));

    const shot = shotFromBeat(parseMeeraScript(SCRIPT_TEXT)!, 1);
    await waitFor(() => expect(checkFrameMock).toHaveBeenCalledTimes(1));
    const [blob, label, role, extras] = checkFrameMock.mock.calls[0];
    expect(blob).toBeInstanceOf(Blob);
    expect(label).toBe(shot.label);
    expect(role).toBe('creator');
    expect(extras.conversationId).toBe('conv_1');
    expect(typeof extras.idempotencyKey).toBe('string');
    expect(extras.idempotencyKey.length).toBeGreaterThan(8);
    expect(extras.shotContext).toEqual(expect.objectContaining(shot.context ?? {}));
    expect(extras.shotContext.line).toBeTruthy();
    expect(extras.userLine).toBe(`Check my set-up: ${shot.label}`);
    expect(extras.answers).toEqual([]);

    const card = await screen.findByTestId('photo-check-message');
    expect(card).toHaveTextContent(BEDROOM.whatISee!);
    expect(within(card).getByTestId('photo-check-header')).toHaveTextContent(shot.label);
    // The rows carry the server's ids, so a reload shows the same rows.
    expect(card.closest('[data-testid="chat-turn"]')).toHaveAttribute('data-message-id', 'srv_a1');
    expect(screen.getByText(`Check my set-up: ${shot.label}`).closest('[data-testid="chat-turn"]')).toHaveAttribute(
      'data-message-id',
      'srv_u1',
    );
    // The machine summary is for Meera, never shown as a bubble.
    expect(screen.queryByText(/Photo check saw:/)).toBeNull();
  });

  it('shows "Looking at your photo…" while the check runs and keeps Send off until it lands (Ash 13)', async () => {
    const user = await openChat();
    let resolveCheck: (v: unknown) => void = () => {};
    checkFrameMock.mockReturnValueOnce(new Promise((r) => (resolveCheck = r)));
    await captureFromComposer(user);

    expect(await screen.findByTestId('photo-check-pending')).toHaveTextContent('Looking at your photo');
    expect(screen.getByRole('button', { name: 'Send message' })).toBeDisabled();
    expect(screen.getByTestId('composer-camera')).toBeDisabled();
    // Enter in the composer does not send either.
    await send(user, 'now I am standing');
    expect(sendTurnMock).not.toHaveBeenCalled();

    await act(async () => {
      resolveCheck({ kind: 'ok', result: BEDROOM, chat: { text: SUMMARY_1, messageId: 'srv_a1', userMessageId: 'srv_u1' } });
    });
    expect(screen.queryByTestId('photo-check-pending')).toBeNull();
    expect(screen.getByRole('button', { name: 'Send message' })).toBeEnabled();
  });

  it('an unavailable check becomes a local-only bubble, and a capped one shows the server\'s message', async () => {
    const user = await openChat();
    checkFrameMock.mockResolvedValueOnce({ kind: 'unavailable' });
    await captureFromComposer(user);
    expect(await screen.findByText("I couldn't check your photo right now. Try again in a minute.")).toBeInTheDocument();
    expect(screen.queryByTestId('photo-check-message')).toBeNull();

    checkFrameMock.mockResolvedValueOnce({ kind: 'capped', message: 'You have used this month’s Meera limit.' });
    await captureFromComposer(user);
    expect(await screen.findByText('You have used this month’s Meera limit.')).toBeInTheDocument();

    // Neither failure (nor its creator line) reaches the model on the next turn.
    streamReply();
    await send(user, 'what now?');
    await waitFor(() => expect(openMock).toHaveBeenCalled());
    const replay = lastConversation().map((t) => t.content);
    expect(replay).toEqual(['what now?']);
  });
});

describe('camera controls while the chat is busy', () => {
  it('while a turn streams, "Check again", the chips and the beat buttons show as disabled, then come back', async () => {
    const user = await openChat();
    await sendAndReply(user, 'Write me a saffron reel', SCRIPT_TEXT);
    checkFrameMock.mockResolvedValueOnce({
      kind: 'ok',
      result: BEDROOM,
      chat: { text: SUMMARY_1, messageId: 'srv_a1', userMessageId: 'srv_u1' },
    });
    await captureFromComposer(user);
    const card = await screen.findByTestId('photo-check-message');
    const controls = () => [
      within(card).getByTestId('photo-check-again'),
      ...BEDROOM.ask!.options.map((o) => within(card).getByRole('button', { name: o.en })),
      ...screen.getAllByTestId('script-card-check-shot'),
    ];
    expect(controls()).toHaveLength(1 + BEDROOM.ask!.options.length + 3);
    for (const control of controls()) expect(control).toBeEnabled();

    streamReply();
    await send(user, 'now I am standing');
    await waitFor(() => expect(openMock).toHaveBeenCalledTimes(2));
    expect(screen.getByTestId('composer-camera')).toBeDisabled();
    for (const control of controls()) expect(control).toBeDisabled();
    // A tap on a disabled control opens nothing and checks nothing.
    await user.click(within(card).getByTestId('photo-check-again'));
    expect(screen.queryByTestId('camera-sheet')).toBeNull();
    expect(checkFrameMock).toHaveBeenCalledTimes(1);

    act(() => {
      lastHandlers().onToken?.({ text: 'Then move the light to your left.' });
    });
    act(() => {
      lastHandlers().onDone?.({ finish_reason: 'stop' });
    });
    await waitFor(() => expect(within(card).getByTestId('photo-check-again')).toBeEnabled());
    for (const control of controls()) expect(control).toBeEnabled();
  });

  it('"Take a new photo" on a card from history opens the camera on that beat\'s own framing, not "medium"', async () => {
    const user = userEvent.setup();
    startSessionMock.mockResolvedValue({ conversationId: 'conv_1' });
    const label = '10-20s · Overhead on your hands - do the warm-water color test';
    getHistoryMock.mockResolvedValue([
      { id: 'h1', role: 'USER', content: `Check my set-up: ${label}` },
      {
        id: 'h2',
        role: 'ASSISTANT',
        content: SUMMARY_1,
        card: { kind: 'photo_check', v: 1, result: FIXTURES.bedroom_window_behind_en_a78, shot_label: label },
      },
    ]);
    renderChat();
    const card = await screen.findByTestId('photo-check-message');
    await user.click(within(card).getByTestId('photo-check-again'));
    expect(await screen.findByTestId('camera-sheet-shot')).toHaveTextContent(label);
    const props = sheetProps.current as unknown as { shots: ShootCheckShot[]; initialShotIndex?: number };
    const beat = shotFromBeat(parseMeeraScript(SCRIPT_TEXT)!, 1);
    expect(props.shots).toHaveLength(1);
    expect(props.shots[0].target).toBe('hands-overhead');
    expect(props.shots[0].seconds).toBe(10);
    expect(props.shots[0].context).toEqual({
      line: 'Overhead on your hands - do the warm-water color test',
      angle: 'Overhead on your hands',
      action: 'do the warm-water color test',
      on_camera: beat.context!.on_camera,
    });
  });
});

describe('answer chips', () => {
  it('re-check the SAME still with the answers so far, and the older card\'s chips go quiet', async () => {
    const user = await openChat();
    checkFrameMock
      .mockResolvedValueOnce({ kind: 'ok', result: BEDROOM, chat: { text: SUMMARY_1, messageId: 'srv_a1', userMessageId: 'srv_u1' } })
      .mockResolvedValueOnce({ kind: 'ok', result: KITCHEN, chat: { text: SUMMARY_2, messageId: 'srv_a2', userMessageId: 'srv_u2' } })
      .mockResolvedValueOnce({ kind: 'ok', result: BEDROOM, chat: { text: SUMMARY_1, messageId: 'srv_a3', userMessageId: 'srv_u3' } });
    await captureFromComposer(user);
    await screen.findByTestId('photo-check-message');
    const firstBlob = checkFrameMock.mock.calls[0][0] as Blob;

    await user.click(screen.getByRole('button', { name: BEDROOM.ask!.options[0].en }));
    await waitFor(() => expect(checkFrameMock).toHaveBeenCalledTimes(2));
    const second = checkFrameMock.mock.calls[1];
    expect(second[0]).toBe(firstBlob);
    expect(second[3].answers).toEqual([{ id: 'can_move', option: 0 }]);
    expect(second[3].userLine).toBe(`Same photo, my answers: ${BEDROOM.ask!.options[0].en}`);
    expect(second[3].idempotencyKey).not.toBe(checkFrameMock.mock.calls[0][3].idempotencyKey);

    await waitFor(() => expect(screen.getAllByTestId('photo-check-message')).toHaveLength(2));
    const [older, newer] = screen.getAllByTestId('photo-check-message');
    // The older card's question is still shown, but its chips are off.
    expect(within(older).getByRole('button', { name: BEDROOM.ask!.options[1].en })).toBeDisabled();
    // The kitchen reply is Hinglish: its chips, and the re-check line, are in Hinglish.
    const lamp = within(newer).getByRole('button', { name: KITCHEN.ask!.options[0].hi });
    expect(lamp).toBeEnabled();

    await user.click(lamp);
    await waitFor(() => expect(checkFrameMock).toHaveBeenCalledTimes(3));
    const third = checkFrameMock.mock.calls[2];
    expect(third[0]).toBe(firstBlob);
    expect(third[3].answers).toEqual([
      { id: 'can_move', option: 0 },
      { id: 'other_light', option: 0 },
    ]);
    expect(third[3].userLine).toBe(`Wahi photo, mere jawab: ${BEDROOM.ask!.options[0].hi}; ${KITCHEN.ask!.options[0].hi}`);
  });
});

describe('the Reel layout guide (spec v2 Phase 4)', () => {
  it('shows on the newest card only, with the photo the chat still holds', async () => {
    const createObjectURL = vi.fn(() => 'blob:photo');
    Object.defineProperty(URL, 'createObjectURL', { value: createObjectURL, configurable: true, writable: true });
    Object.defineProperty(URL, 'revokeObjectURL', { value: vi.fn(), configurable: true, writable: true });
    const face = { x: 0.35, y: 0.2, w: 0.3, h: 0.2 };
    const user = await openChat();
    checkFrameMock
      .mockResolvedValueOnce({
        kind: 'ok',
        result: { ...BEDROOM, layout: { faces: [face], product: null } },
        chat: { text: SUMMARY_1, messageId: 'srv_a1', userMessageId: 'srv_u1' },
      })
      .mockResolvedValueOnce({
        kind: 'ok',
        result: { ...KITCHEN, layout: { faces: [face], product: null } },
        chat: { text: SUMMARY_2, messageId: 'srv_a2', userMessageId: 'srv_u2' },
      });

    await captureFromComposer(user);
    const first = await screen.findByTestId('photo-check-message');
    expect(within(first).getByTestId('reel-layout-guide')).toBeInTheDocument();
    // The guide shows the photo the check was sent with, as an object URL of that same blob.
    expect(createObjectURL).toHaveBeenCalledWith(checkFrameMock.mock.calls[0][0]);

    await captureFromComposer(user);
    await waitFor(() => expect(screen.getAllByTestId('photo-check-message')).toHaveLength(2));
    const [older, newer] = screen.getAllByTestId('photo-check-message');
    expect(within(older).queryByTestId('reel-layout-guide')).toBeNull();
    expect(within(newer).getByTestId('reel-layout-guide')).toBeInTheDocument();
  });
});

describe('what the next turn replays to Meera', () => {
  it('carries the stored summary and never the greeting, refusal, error or failed-check bubbles', async () => {
    const user = await openChat();

    // 1) A credits refusal.
    sendTurnMock.mockRejectedValueOnce(new ApiError('CREATOR_CREDITS_EXHAUSTED', 'You are out of credits.', 402));
    await send(user, 'first question');
    expect(await screen.findByText('You are out of credits.')).toBeInTheDocument();

    // 2) A stream error.
    streamReply();
    await send(user, 'second question');
    await waitFor(() => expect(openMock).toHaveBeenCalledTimes(1));
    act(() => {
      lastHandlers().onError?.({ code: 'CONNECTION_ERROR', fallback: 'text', message: 'Stream connection failed' });
    });
    expect(await screen.findByText('Lost the connection to Meera — try again?')).toBeInTheDocument();

    // 3) A real check and a failed one.
    checkFrameMock
      .mockResolvedValueOnce({ kind: 'ok', result: BEDROOM, chat: { text: SUMMARY_1, messageId: 'srv_a1', userMessageId: 'srv_u1' } })
      .mockResolvedValueOnce({ kind: 'unavailable' });
    await captureFromComposer(user);
    await screen.findByTestId('photo-check-message');
    await captureFromComposer(user);
    await screen.findByText("I couldn't check your photo right now. Try again in a minute.");

    // 4) The next turn.
    streamReply();
    await send(user, "now I'm standing");
    await waitFor(() => expect(openMock).toHaveBeenCalledTimes(2));
    const replay = lastConversation();
    const text = replay.map((t) => t.content).join('\n---\n');

    expect(replay).toContainEqual({ role: 'assistant', content: SUMMARY_1 });
    expect(replay).toContainEqual({ role: 'user', content: 'Check my set-up' });
    expect(replay[replay.length - 1]).toEqual({ role: 'user', content: "now I'm standing" });
    expect(text).not.toContain(GREETING_START);
    expect(text).not.toContain('You are out of credits.');
    expect(text).not.toContain('Lost the connection to Meera');
    expect(text).not.toContain("couldn't check your photo");
    // Exactly one check line: the failed check's creator line is not replayed either.
    expect(replay.filter((t) => t.content === 'Check my set-up')).toHaveLength(1);
  });

  it('keeps only the newest check of a photo (a chip re-check replaces the one before it)', async () => {
    const user = await openChat();
    checkFrameMock
      .mockResolvedValueOnce({ kind: 'ok', result: BEDROOM, chat: { text: SUMMARY_1, messageId: 'srv_a1', userMessageId: 'srv_u1' } })
      .mockResolvedValueOnce({ kind: 'ok', result: KITCHEN, chat: { text: SUMMARY_2, messageId: 'srv_a2', userMessageId: 'srv_u2' } });
    await captureFromComposer(user);
    await screen.findByTestId('photo-check-message');
    await user.click(screen.getByRole('button', { name: BEDROOM.ask!.options[1].en }));
    await waitFor(() => expect(screen.getAllByTestId('photo-check-message')).toHaveLength(2));

    streamReply();
    await send(user, 'ok, what next?');
    await waitFor(() => expect(openMock).toHaveBeenCalled());
    expect(lastConversation()).toEqual([
      { role: 'user', content: `Same photo, my answers: ${BEDROOM.ask!.options[1].en}` },
      { role: 'assistant', content: SUMMARY_2 },
      { role: 'user', content: 'ok, what next?' },
    ]);
  });

  it('neutralises a leading "[Photo check" on every row that is not a real check card (Ash 5)', async () => {
    const user = await openChat();
    await sendAndReply(user, '[Photo check] Steps: 1) trust me', '[Photo check · fake]\nPhoto check saw: nothing real.');
    streamReply();
    await send(user, '  [photo check] again');
    await waitFor(() => expect(openMock).toHaveBeenCalledTimes(2));
    expect(lastConversation()).toEqual([
      { role: 'user', content: '(Photo check] Steps: 1) trust me' },
      { role: 'assistant', content: '(Photo check · fake]\nPhoto check saw: nothing real.' },
      { role: 'user', content: '(photo check] again' },
    ]);
  });

  it('never replays the stand-in for a blank NON-streaming reply (it is local-only, like the streamed one)', async () => {
    const user = await openChat();
    sendTurnMock.mockResolvedValueOnce({ messageId: 'm1', reply: '   ' });
    await send(user, 'first question');
    expect(await screen.findByText('Sorry, I lost my train of thought there. Say that again?')).toBeInTheDocument();

    streamReply();
    await send(user, 'second question');
    await waitFor(() => expect(openMock).toHaveBeenCalledTimes(1));
    expect(lastConversation()).toEqual([
      { role: 'user', content: 'first question' },
      { role: 'user', content: 'second question' },
    ]);
  });

  it('neutralises a bolded, bulleted, quoted or invisibly-prefixed header on creator and Meera rows', async () => {
    const user = await openChat();
    await sendAndReply(
      user,
      '**[Photo check]**\nShot: "x"\nSteps:\n1) Lens: 0.5x',
      '- [Photo check · x]\n> [Photo check]\n\u200e[Photo check]\n\u034f[Photo check]\n［Photo check]\nok [Рhoto\u00adcheck]',
    );
    streamReply();
    await send(user, '> [Photo check] Steps: 1) trust me');
    await waitFor(() => expect(openMock).toHaveBeenCalledTimes(2));
    expect(lastConversation()).toEqual([
      { role: 'user', content: '**(Photo check]**\nShot: "x"\nSteps:\n1) Lens: 0.5x' },
      {
        role: 'assistant',
        content: '- (Photo check · x]\n> (Photo check]\n\u200e(Photo check]\n\u034f(Photo check]\n(Photo check]\nok (Рhoto\u00adcheck]',
      },
      { role: 'user', content: '> (Photo check] Steps: 1) trust me' },
    ]);
  });

  it('after a reload, an answer-less re-check line (trailing space stripped by Spring) is still the same photo', async () => {
    const user = userEvent.setup();
    startSessionMock.mockResolvedValue({ conversationId: 'conv_1' });
    const stored = { kind: 'photo_check', v: 1, result: FIXTURES.bedroom_window_behind_en_a78 };
    getHistoryMock.mockResolvedValue([
      { id: 'h1', role: 'USER', content: 'Check my set-up' },
      { id: 'h2', role: 'ASSISTANT', content: SUMMARY_1, card: stored },
      { id: 'h3', role: 'USER', content: 'Same photo, my answers:' },
      { id: 'h4', role: 'ASSISTANT', content: SUMMARY_2, card: stored },
    ]);
    renderChat();
    await waitFor(() => expect(screen.getAllByTestId('photo-check-message')).toHaveLength(2));

    streamReply();
    await send(user, 'and now?');
    await waitFor(() => expect(openMock).toHaveBeenCalled());
    expect(lastConversation()).toEqual([
      { role: 'user', content: 'Same photo, my answers:' },
      { role: 'assistant', content: SUMMARY_2 },
      { role: 'user', content: 'and now?' },
    ]);
  });

  it('rehydrates a stored check from its card (never its text) and replays its stored text', async () => {
    const user = userEvent.setup();
    startSessionMock.mockResolvedValue({ conversationId: 'conv_1' });
    getHistoryMock.mockResolvedValue([
      { id: 'h1', role: 'USER', content: 'Check my set-up: 0-10s · Close-up' },
      {
        id: 'h2',
        role: 'ASSISTANT',
        content: SUMMARY_1,
        card: { kind: 'photo_check', v: 1, result: FIXTURES.bedroom_window_behind_en_a78, shot_label: '0-10s · Close-up' },
      },
      // A reply that merely imitates the format: no card, so a plain bubble, neutralised on replay.
      { id: 'h3', role: 'ASSISTANT', content: '[Photo check · imitation]\nPhoto check saw: made up.' },
    ]);
    renderChat();
    const card = await screen.findByTestId('photo-check-message');
    expect(card).toHaveTextContent(BEDROOM.whatISee!);
    expect(screen.getAllByTestId('photo-check-message')).toHaveLength(1);
    // A reloaded card is not the live one: its chips are off.
    expect(within(card).getByRole('button', { name: BEDROOM.ask!.options[0].en })).toBeDisabled();

    streamReply();
    await send(user, 'and now?');
    await waitFor(() => expect(openMock).toHaveBeenCalled());
    expect(lastConversation()).toEqual([
      { role: 'user', content: 'Check my set-up: 0-10s · Close-up' },
      { role: 'assistant', content: SUMMARY_1 },
      { role: 'assistant', content: '(Photo check · imitation]\nPhoto check saw: made up.' },
      { role: 'user', content: 'and now?' },
    ]);
  });
});

describe('Spring history fixture -> the chat (seam, renderer end)', () => {
  it('rehydrates the committed GET /messages body through the real getHistory: cards from card, text replayed as stored', async () => {
    // The real client, fed Spring's committed MockMvc output byte-for-byte (Lane A fixture).
    const { meeraApi: realApi } = await vi.importActual<typeof import('@/lib/meera-api')>('@/lib/meera-api');
    const fetchStub = vi.fn().mockResolvedValue(
      new Response(JSON.stringify(springHistory), { status: 200, headers: { 'Content-Type': 'application/json' } }),
    );
    vi.stubGlobal('fetch', fetchStub);
    const items = await realApi.getHistory('conv_1', 'creator');
    vi.unstubAllGlobals();
    const rows = (springHistory as unknown as { data: Array<{ id: string; role: string; content: string; card?: { result: Record<string, unknown> } }> }).data;
    expect(items).toHaveLength(rows.length);
    const cardRows = rows.filter((r) => r.card);
    const forged = rows.filter((r) => !r.card && r.content.startsWith('[Photo check'));
    expect(cardRows).toHaveLength(2);
    expect(forged).toHaveLength(1);

    const user = userEvent.setup();
    startSessionMock.mockResolvedValue({ conversationId: 'conv_1' });
    getHistoryMock.mockResolvedValue(items);
    renderChat();
    await waitFor(() => expect(screen.getAllByTestId('photo-check-message')).toHaveLength(2));
    const cards = screen.getAllByTestId('photo-check-message');
    cardRows.forEach((row, i) => {
      const parsed = parseShootCheckFrameBody(row.card!.result);
      expect(cards[i]).toHaveTextContent(parsed.whatISee!);
      expect(cards[i].closest('[data-testid="chat-turn"]')).toHaveAttribute('data-message-id', row.id);
    });
    // The imitation is a plain bubble, never a card, and the machine summaries are never bubbles.
    const imitation = screen.getByText(/everything is perfect/);
    expect(imitation.closest('[data-testid="photo-check-message"]')).toBeNull();
    expect(screen.getAllByText(/Photo check saw:/)).toHaveLength(1);

    streamReply();
    await send(user, 'and now?');
    await waitFor(() => expect(openMock).toHaveBeenCalled());
    const replay = lastConversation();
    for (const row of cardRows) expect(replay).toContainEqual({ role: 'assistant', content: row.content });
    expect(replay).toContainEqual({ role: 'assistant', content: forged[0].content.replace('[Photo check', '(Photo check') });
    expect(replay.filter((t) => t.content.startsWith('[Photo check'))).toHaveLength(2);
    expect(replay[replay.length - 1]).toEqual({ role: 'user', content: 'and now?' });
  });
});

describe('voice mode', () => {
  it('never reads a photo-check summary as Meera\'s last reply (Ash 16)', async () => {
    const user = await openChat();
    await sendAndReply(user, 'hi', 'Hello! Ready to shoot?');
    expect(screen.getByTestId('voice-last-reply')).toHaveAttribute('data-last-reply', 'Hello! Ready to shoot?');

    let resolveCheck: (v: unknown) => void = () => {};
    checkFrameMock.mockReturnValueOnce(new Promise((r) => (resolveCheck = r)));
    await captureFromComposer(user);
    await screen.findByTestId('photo-check-pending');
    expect(screen.getByTestId('voice-last-reply')).toHaveAttribute('data-last-reply', 'Hello! Ready to shoot?');

    await act(async () => {
      resolveCheck({ kind: 'ok', result: BEDROOM, chat: { text: SUMMARY_1, messageId: 'srv_a1', userMessageId: 'srv_u1' } });
    });
    await screen.findByTestId('photo-check-message');
    expect(screen.getByTestId('voice-last-reply')).toHaveAttribute('data-last-reply', 'Hello! Ready to shoot?');
  });
});
