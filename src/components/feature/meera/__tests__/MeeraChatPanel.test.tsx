/**
 * MeeraChatPanel — P1-7 / P1-8 / P1-9 regression specs
 * ----------------------------------------------------------------------------
 * P1-8: `onToolResult` dropped every `tool_result` whose name wasn't in
 *   MEERA_FUNCTION_CALLS, and that list omitted `analyze_site`. `analyze_site`
 *   is a REAL live tool — `get_tool_schemas()` offers it on every turn
 *   (influora-ai/app/tools/schemas.py) and the loop runs it in-process
 *   (`perform_site_analysis`). So whenever Meera actually read a brand's store
 *   mid-chat, the result was silently discarded: nothing in the chat, and
 *   `stagePayloads.snapshot` never populated. Every assertion below about the
 *   store card, and the `onFunctionCall('analyze_site', …)` assertion, fails
 *   against that code at the early-return — the card is never rendered at all,
 *   so this is not a styling/markup difference, it is absence.
 *
 * P1-7: `request_payment` and `confirm_launch` are the two money tools Meera is
 *   deliberately never given (filtered out of `get_tool_schemas()`; outside the
 *   on-behalf token scope). That is correct and must stay. What was broken is
 *   that the UI left the brand at a step nothing could advance: the only way
 *   either name reaches the browser is loop.py's scope-rejection tool_result,
 *   which rendered as a raw red "Internal request rejected: …" with no route
 *   anywhere, and a successful `create_campaign` draft ended the conversation
 *   with no pointer to the controls that DO fund and launch. The hand-off tests
 *   below click a real link and assert the destination route actually renders —
 *   against the old code `getByRole('link', …)` finds nothing, because no link
 *   existed.
 *
 * P1-9 is a comment correction (the old one asserted `analyze_site` "is NOT a
 * real backend tool"); it has no separate behavioural test — the P1-8 tests are
 * the proof the claim was false.
 *
 * Run: npx vitest run src/components/feature/meera/__tests__/MeeraChatPanel.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, act } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';

import type { MeeraStreamHandlers } from '@/hooks/useMeeraStream';
import type { MeeraToolResultEvent } from '@/lib/meera-api';

// jsdom implements no Element.scrollTo, and the panel scrolls its message list
// on every render — without this the first effect throws before any assertion.
if (!('scrollTo' in Element.prototype)) {
  Object.defineProperty(Element.prototype, 'scrollTo', {
    value: () => {},
    writable: true,
    configurable: true,
  });
}

const { streamOpenMock } = vi.hoisted(() => ({ streamOpenMock: vi.fn() }));

vi.mock('@/hooks/useMeeraStream', () => ({
  useMeeraStream: () => ({
    status: 'idle',
    open: streamOpenMock,
    close: vi.fn(),
    lastError: null,
  }),
}));

// TTS is additive and irrelevant here; jsdom has no speechSynthesis.
vi.mock('@/hooks/useVoiceOutput', () => ({
  useVoiceOutput: () => ({
    supported: false,
    enabled: false,
    setEnabled: vi.fn(),
    isSpeaking: false,
    speak: vi.fn(),
    speakSequence: vi.fn(),
    stop: vi.fn(),
  }),
}));

// vitest.config.ts pins VITE_API_MODE=mock for the whole suite; these defects
// only exist on the LIVE stream path, so isApiLive has to be forced here.
vi.mock('@/lib/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/api')>();
  return { ...actual, isApiLive: () => true };
});

vi.mock('@/lib/meera-api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/meera-api')>();
  return {
    ...actual,
    meeraApi: {
      ...actual.meeraApi,
      startSession: vi.fn(),
      getHistory: vi.fn(),
      sendTurn: vi.fn(),
      getMessagesAfter: vi.fn(),
      logOptionTapped: vi.fn(),
    },
  };
});

import { meeraApi } from '@/lib/meera-api';
import { MeeraChatPanel } from '../MeeraChatPanel';

const startSessionMock = vi.mocked(meeraApi.startSession);
const getHistoryMock = vi.mocked(meeraApi.getHistory);
const sendTurnMock = vi.mocked(meeraApi.sendTurn);

/**
 * A real successful `analyze_site` payload
 * (influora-ai/app/routes/analyze_site.py::perform_site_analysis's return).
 */
const ANALYZE_SITE_OK = {
  success: true,
  data: {
    source_url: 'https://www.kiaraskincare.in/collections/all',
    niche_tags: ['skincare', 'clean beauty'],
    tone_dial: 'warm',
    brand_color: '#2f6f4e',
    product_catalog: [
      { name: 'Vitamin C Serum', price: 1299, currency: 'INR' },
      { name: 'Night Repair Cream', price: 1899, currency: 'INR' },
      { name: 'Gentle Cleanser', price: 699, currency: 'INR' },
      { name: 'Sunscreen SPF 50', price: 899, currency: 'INR' },
    ],
  },
};

function renderPanel() {
  const onFunctionCall = vi.fn();
  render(
    <MemoryRouter initialEntries={['/brand/meera']}>
      <Routes>
        <Route path="/brand/meera" element={<MeeraChatPanel onFunctionCall={onFunctionCall} />} />
        <Route path="/brand/wallet" element={<div>WALLET PAGE REACHED</div>} />
        <Route path="/brand/campaigns" element={<div>CAMPAIGNS PAGE REACHED</div>} />
      </Routes>
    </MemoryRouter>,
  );
  return { onFunctionCall };
}

/**
 * Drive one real live turn: wait for the session to settle, type, send, and
 * hand back the stream handlers the component registered. Nothing here is
 * faked past the network edge — the panel's own send path runs.
 */
async function startTurn(user: ReturnType<typeof userEvent.setup>): Promise<MeeraStreamHandlers> {
  const textbox = await screen.findByRole('textbox');
  await waitFor(() => expect(textbox).toBeEnabled());

  await user.type(textbox, 'here is my store');
  await user.click(screen.getByRole('button', { name: 'Send message' }));

  await waitFor(() => expect(streamOpenMock).toHaveBeenCalled());
  return streamOpenMock.mock.calls[0][2] as MeeraStreamHandlers;
}

async function emit(handlers: MeeraStreamHandlers, event: MeeraToolResultEvent) {
  await act(async () => {
    handlers.onToolResult?.(event);
  });
}

describe('MeeraChatPanel — live tool_result handling', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    window.localStorage.clear();

    startSessionMock.mockResolvedValue({
      conversationId: 'conv_test_0001',
      status: 'ACTIVE',
      brandProfileStatus: 'READY',
      credits: { remaining: 100, unlimited: false },
    });
    getHistoryMock.mockResolvedValue([]);
    // No `reply` → the component takes the stream-first path and calls
    // stream.open(), which is where tool_results arrive.
    sendTurnMock.mockResolvedValue({
      messageId: 'msg_0001',
      streamToken: 'stream_token_test',
      streamUrl: 'https://ai.test.internal/chat',
      creditsRemaining: 99,
      workspaceId: 'ws_0001',
      onBehalfToken: 'onbehalf_test',
    });
  });

  // -------------------------------------------------------------------------
  // P1-8 — analyze_site results must reach the brand
  // -------------------------------------------------------------------------

  it('P1-8: renders the store analysis when analyze_site returns (it was dropped entirely)', async () => {
    const user = userEvent.setup({ delay: null });
    renderPanel();
    const handlers = await startTurn(user);

    await emit(handlers, { name: 'analyze_site', status: 'ok', data: ANALYZE_SITE_OK });

    // The real products Meera read off the page — the whole point of the tool.
    expect(await screen.findByText('Vitamin C Serum')).toBeInTheDocument();
    expect(screen.getByText('Night Repair Cream')).toBeInTheDocument();
    expect(screen.getByText(/1,299/)).toBeInTheDocument();
    // Host, niche tag and the overflow line, the same grammar the other
    // inline tool cards use.
    expect(screen.getByText(/Read kiaraskincare\.in/)).toBeInTheDocument();
    expect(screen.getByText('skincare')).toBeInTheDocument();
    expect(screen.getByText('+1 more on canvas')).toBeInTheDocument();
  });

  it('P1-8: forwards the analyze_site payload up so the snapshot stage gets real data', async () => {
    const user = userEvent.setup({ delay: null });
    const { onFunctionCall } = renderPanel();
    const handlers = await startTurn(user);

    await emit(handlers, { name: 'analyze_site', status: 'ok', data: ANALYZE_SITE_OK });

    // StageSnapshot's `toolResult` effect (its refetch-on-analysis path) can
    // only fire if this call happens. It never did.
    await waitFor(() => expect(onFunctionCall).toHaveBeenCalledWith('analyze_site', ANALYZE_SITE_OK));
  });

  it('P1-8: surfaces the real reason when analyze_site fails, not a generic failure', async () => {
    const user = userEvent.setup({ delay: null });
    renderPanel();
    const handlers = await startTurn(user);

    // analyze_site.py returns a NESTED error object, unlike the flat Spring
    // `{error, message}` shape — so the old `toolErrorMessage` missed it even
    // once the result was no longer dropped.
    await emit(handlers, {
      name: 'analyze_site',
      status: 'error',
      data: { success: false, error: { code: 'empty_page', message: 'no readable content found' } },
    });

    expect(await screen.findByText('no readable content found')).toBeInTheDocument();
    expect(screen.queryByText(/Failed to run Analyze Site/i)).not.toBeInTheDocument();
  });

  // -------------------------------------------------------------------------
  // P1-7 — the two steps Meera cannot take must hand off to the human control
  // -------------------------------------------------------------------------

  it('P1-7: a scope-rejected request_payment hands the brand a working route to the wallet', async () => {
    const user = userEvent.setup({ delay: null });
    renderPanel();
    const handlers = await startTurn(user);

    await emit(handlers, {
      name: 'request_payment',
      status: 'error',
      data: {
        error: 'ON_BEHALF_SCOPE_INSUFFICIENT',
        message: 'Internal request rejected: ON_BEHALF_SCOPE_INSUFFICIENT',
      },
    });

    // The raw backend code is not an instruction a brand can act on.
    expect(screen.queryByText(/ON_BEHALF_SCOPE_INSUFFICIENT/)).not.toBeInTheDocument();

    // Not just a card-shaped div: click it and prove it goes somewhere real.
    const link = await screen.findByRole('link', { name: /Open your wallet/i });
    await user.click(link);
    expect(await screen.findByText('WALLET PAGE REACHED')).toBeInTheDocument();
  });

  it('P1-7: a scope-rejected confirm_launch routes to the campaign page instead of dead-ending', async () => {
    const user = userEvent.setup({ delay: null });
    renderPanel();
    const handlers = await startTurn(user);

    await emit(handlers, {
      name: 'confirm_launch',
      status: 'error',
      data: { error: 'ON_BEHALF_SCOPE_INSUFFICIENT', message: 'Internal request rejected' },
    });

    const link = await screen.findByRole('link', { name: /Open your campaigns/i });
    await user.click(link);
    expect(await screen.findByText('CAMPAIGNS PAGE REACHED')).toBeInTheDocument();
  });

  it('P1-7: a created draft points at the funding control the brand actually uses', async () => {
    const user = userEvent.setup({ delay: null });
    renderPanel();
    const handlers = await startTurn(user);

    // The reachable path: create_campaign is a live, non-money tool. After it,
    // funding and launching are the human's steps and nothing said so.
    await emit(handlers, {
      name: 'create_campaign',
      status: 'ok',
      data: { campaignId: 'camp_0001', status: 'DRAFT' },
    });

    // The existing draft card is untouched…
    expect(await screen.findByText(/Draft ready/i)).toBeInTheDocument();
    // …and the next step now has a real destination.
    const link = await screen.findByRole('link', { name: /Open your wallet/i });
    await user.click(link);
    expect(await screen.findByText('WALLET PAGE REACHED')).toBeInTheDocument();
  });

  it('P1-7: the hand-off copy uses the Secure Payments vocabulary, never "escrow"', async () => {
    const user = userEvent.setup({ delay: null });
    renderPanel();
    const handlers = await startTurn(user);

    await emit(handlers, {
      name: 'request_payment',
      status: 'error',
      data: { error: 'ON_BEHALF_SCOPE_INSUFFICIENT', message: 'Internal request rejected' },
    });

    expect(await screen.findByText(/Secure Campaign Funds card/i)).toBeInTheDocument();
    expect(document.body.textContent ?? '').not.toMatch(/escrow/i);
  });

  // -------------------------------------------------------------------------
  // Guard — widening the allow-list must not open it to everything
  // -------------------------------------------------------------------------

  it('still ignores a tool_result that is not part of the chat contract', async () => {
    const user = userEvent.setup({ delay: null });
    renderPanel();
    const handlers = await startTurn(user);

    // analyze_creator_content is deliberately outside the chat function-calling
    // contract (schemas.py) — it must not start rendering cards in chat.
    await emit(handlers, { name: 'analyze_creator_content', status: 'ok', data: { score: 90 } });

    expect(screen.queryByText(/Analyze Creator Content/i)).not.toBeInTheDocument();
  });
});
