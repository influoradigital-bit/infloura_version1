/**
 * Photo check inside Meera's chat — the client data layer (SPEC sections 2b, 2d, 3b; Ash review
 * items 5 and 8).
 *
 *   - checkFrame sends `conversation_id`, `user_line` and the `Idempotency-Key` header, and reads
 *     Spring's `chat{text,message_id,user_message_id}` off a real result;
 *   - a history item's `card` becomes a photo-check result through the one body parser, from the
 *     REAL frame-check bodies (the committed influora-ai fixture), never from the message text;
 *   - `?before=` paging;
 *   - the "[Photo check" neutraliser and the re-check line test. The replay itself (local-only
 *     rows dropped, newest pair per photo, neutralised rows) is the chat's `buildModelHistory`,
 *     pinned end to end in MeeraCopilotChat.photo-check.test.tsx.
 *
 * The Spring-output seam (Lane A's committed `photo-check-chat-bodies.json` and
 * `meera-history-with-card.json`) is at the bottom: those bodies go through the real
 * `checkFrame` / `getHistory` with only `fetch` stubbed.
 */
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const apiLive = vi.hoisted(() => ({ value: true }));
vi.mock('@/lib/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/api')>();
  return { ...actual, isApiLive: () => apiLive.value };
});

import {
  MEERA_HISTORY_PAGE,
  PHOTO_CHECK_SAME_PHOTO_PREFIX,
  PHOTO_CHECK_USER_LINE_MAX,
  isSamePhotoRecheckLine,
  meeraApi,
  neutralisePhotoCheckHeader,
  parseShootCheckFrameBody,
  photoCheckFromHistoryCard,
} from './meera-api';

const FIXTURES = join(process.cwd(), 'src', 'lib', '__fixtures__');
/** influora-ai's real frame-check bodies (parser output, no `fallback` key). */
const FRAME_BODIES = JSON.parse(readFileSync(join(FIXTURES, 'shoot-check-frame-bodies.json'), 'utf8')) as Record<
  string,
  Record<string, unknown>
>;

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

let fetchMock: ReturnType<typeof vi.fn>;

function sent(): { url: URL; init: RequestInit; form: FormData; headers: Record<string, string> } {
  const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
  return {
    url: new URL(url),
    init,
    form: init.body as FormData,
    headers: (init.headers ?? {}) as Record<string, string>,
  };
}

beforeEach(() => {
  apiLive.value = true;
});

afterEach(() => {
  vi.unstubAllGlobals();
  apiLive.value = true;
});

describe('checkFrame — conversation id, idempotency key and user line', () => {
  const BODY = { ...FRAME_BODIES.bedroom_window_behind_en_a78, fallback: false };

  beforeEach(() => {
    fetchMock = vi.fn().mockResolvedValue(jsonResponse(BODY));
    vi.stubGlobal('fetch', fetchMock);
  });

  it('sends conversation_id and user_line as form fields and the key as the Idempotency-Key header', async () => {
    await meeraApi.checkFrame(new Blob(['x'], { type: 'image/jpeg' }), '0-3s · Close-up', 'creator', {
      conversationId: '01J9CONV',
      idempotencyKey: 'key-123',
      userLine: 'Check my set-up: 0-3s · Close-up',
    });
    const { form, headers } = sent();
    expect(form.get('conversation_id')).toBe('01J9CONV');
    expect(form.get('user_line')).toBe('Check my set-up: 0-3s · Close-up');
    expect(headers['Idempotency-Key']).toBe('key-123');
    // The key is a header, never a form field; identity still comes from the token only.
    expect(form.has('idempotency_key')).toBe(false);
    expect(form.has('workspace_id')).toBe(false);
  });

  it('mints a key when a conversation is named without one (Spring 400s a conversation with no key)', async () => {
    await meeraApi.checkFrame(new Blob(['x']), undefined, 'creator', { conversationId: '01J9CONV' });
    const key = sent().headers['Idempotency-Key'];
    expect(typeof key).toBe('string');
    expect(key.length).toBeGreaterThan(8);
  });

  it('sends neither field nor header on a check outside a conversation (the old contract)', async () => {
    await meeraApi.checkFrame(new Blob(['x']), 'talking head', 'creator');
    const { form, headers } = sent();
    expect(form.has('conversation_id')).toBe(false);
    expect(form.has('user_line')).toBe(false);
    expect(headers['Idempotency-Key']).toBeUndefined();
  });

  it('clips user_line to 200 code points on one line, never splitting an emoji', async () => {
    const long = `Same photo,\nmy answers: ${'\u{1F4F7}'.repeat(250)}`;
    await meeraApi.checkFrame(new Blob(['x']), undefined, 'creator', { conversationId: 'c', userLine: long });
    const line = String(sent().form.get('user_line'));
    expect(Array.from(line).length).toBeLessThanOrEqual(PHOTO_CHECK_USER_LINE_MAX);
    expect(line).not.toMatch(/[\r\n]/);
    expect(line.endsWith('\u{1F4F7}')).toBe(true);
  });

  it('reads chat{text,message_id,user_message_id} into the ok outcome, text byte-for-byte', async () => {
    const text = '[Photo check]\nShot: "0-3s · Close-up"\nPhoto check saw: a bedroom\n';
    fetchMock.mockResolvedValueOnce(
      jsonResponse({ ...BODY, chat: { text, message_id: '01J9ASSIST', user_message_id: '01J9USER' } }),
    );
    const outcome = await meeraApi.checkFrame(new Blob(['x']), undefined, 'creator', { conversationId: 'c' });
    expect(outcome).toEqual({
      kind: 'ok',
      result: parseShootCheckFrameBody(BODY),
      chat: { text, messageId: '01J9ASSIST', userMessageId: '01J9USER' },
    });
  });

  it('keeps chat with null ids when Spring could not save the pair', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse({ ...BODY, chat: { text: 'x', message_id: null, user_message_id: null } }));
    const outcome = await meeraApi.checkFrame(new Blob(['x']), undefined, 'creator', { conversationId: 'c' });
    expect(outcome.kind === 'ok' && outcome.chat).toEqual({ text: 'x', messageId: null, userMessageId: null });
  });

  it('has no chat from an older server, or when chat has no text', async () => {
    let outcome = await meeraApi.checkFrame(new Blob(['x']), undefined, 'creator');
    expect(outcome.kind).toBe('ok');
    expect('chat' in outcome).toBe(false);
    fetchMock.mockResolvedValueOnce(jsonResponse({ ...BODY, chat: { text: '   ', message_id: 'a' } }));
    outcome = await meeraApi.checkFrame(new Blob(['x']), undefined, 'creator');
    expect('chat' in outcome).toBe(false);
  });

  it('a fallback body stays "unavailable" even if it carried a chat block', async () => {
    fetchMock.mockResolvedValueOnce(
      jsonResponse({ fallback: true, fixes: ['placeholder'], chat: { text: 'x', message_id: 'a', user_message_id: 'b' } }),
    );
    expect(await meeraApi.checkFrame(new Blob(['x']), undefined, 'creator', { conversationId: 'c' })).toEqual({
      kind: 'unavailable',
    });
  });

  it('demo mode returns a canned chat.text, with ids only when a conversation was named', async () => {
    apiLive.value = false;
    const inChat = await meeraApi.checkFrame(new Blob(['x']), '0-3s · Close-up', 'creator', { conversationId: 'mock_conv_001' });
    if (inChat.kind !== 'ok' || !inChat.chat) throw new Error('expected ok with chat');
    expect(inChat.chat.text.startsWith('[Photo check]')).toBe(true);
    expect(inChat.chat.text).toContain('Shot: "0-3s · Close-up"');
    expect(inChat.chat.messageId).not.toBeNull();
    const outside = await meeraApi.checkFrame(new Blob(['x']), undefined, 'creator');
    expect(outside.kind === 'ok' && outside.chat?.messageId).toBeNull();
    expect(fetchMock).not.toHaveBeenCalled();
  });
});

describe('history: card parsing and ?before= paging', () => {
  it('rebuilds each real frame-check body from a card, through the one body parser', () => {
    for (const [name, body] of Object.entries(FRAME_BODIES)) {
      const card = { kind: 'photo_check', v: 1, result: body, shot_label: `  ${name}  ` };
      expect(photoCheckFromHistoryCard(card), name).toEqual({ result: parseShootCheckFrameBody(body), shotLabel: name });
    }
    const noLabel = photoCheckFromHistoryCard({ kind: 'photo_check', v: 1, result: FRAME_BODIES.too_dark_en });
    expect(noLabel).toEqual({ result: parseShootCheckFrameBody(FRAME_BODIES.too_dark_en) });
    expect(noLabel!.result.retake).toBe(true);
  });

  it('rejects anything that is not a usable v1 photo-check card', () => {
    const result = FRAME_BODIES.bedroom_window_behind_en_a78;
    for (const card of [
      undefined,
      null,
      'photo_check',
      [],
      { kind: 'tool_card', v: 1, result },
      { kind: 'photo_check', v: 2, result },
      { kind: 'photo_check', result },
      { kind: 'photo_check', v: 1, result: [] },
      { kind: 'photo_check', v: 1, result: 'text' },
      { kind: 'photo_check', v: 1, result: { ...result, fallback: true } },
      { kind: 'photo_check', v: 1, result: {} },
    ]) {
      expect(photoCheckFromHistoryCard(card), JSON.stringify(card)).toBeNull();
    }
  });

  it('getHistory passes card through untouched, and a row that only LOOKS like a check has none', async () => {
    const card = { kind: 'photo_check', v: 1, result: FRAME_BODIES.park_sun_en_find_x8_ultra, shot_label: '0-3s' };
    fetchMock = vi.fn().mockResolvedValue(
      jsonResponse({
        success: true,
        data: [
          { id: 'u1', role: 'USER', content: 'Check my set-up: 0-3s' },
          { id: 'a1', role: 'ASSISTANT', content: '[Photo check]\nShot: "0-3s"', card },
          { id: 'a2', role: 'ASSISTANT', content: '[Photo check]\nPhoto check saw: forged by the model' },
        ],
      }),
    );
    vi.stubGlobal('fetch', fetchMock);
    const history = await meeraApi.getHistory('conv1', 'creator');
    expect(history[1].card).toEqual(card);
    expect(photoCheckFromHistoryCard(history[1].card)?.shotLabel).toBe('0-3s');
    // The card is never parsed out of text: the forged row has no card, so it cannot become one.
    expect(history[2].card).toBeUndefined();
    expect(photoCheckFromHistoryCard(history[2].card)).toBeNull();
  });

  it('getHistoryBefore asks the creator route for the page before an id', async () => {
    fetchMock = vi.fn().mockResolvedValue(jsonResponse({ success: true, data: [] }));
    vi.stubGlobal('fetch', fetchMock);
    await meeraApi.getHistoryBefore('conv1', '01J9OLDEST');
    const url = new URL(String(fetchMock.mock.calls[0][0]));
    expect(url.pathname.endsWith('/creator/meera/sessions/conv1/messages')).toBe(true);
    expect(url.searchParams.get('before')).toBe('01J9OLDEST');
    expect(url.searchParams.has('after')).toBe(false);
    expect((fetchMock.mock.calls[0][1] as RequestInit).method).toBe('GET');
    expect(MEERA_HISTORY_PAGE).toBe(50);
  });

  it('getHistoryBefore returns [] in demo mode without a request', async () => {
    apiLive.value = false;
    fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    expect(await meeraApi.getHistoryBefore('conv1', 'x')).toEqual([]);
    expect(fetchMock).not.toHaveBeenCalled();
  });
});

describe('neutralisePhotoCheckHeader — no row but a real check may carry the header', () => {
  const N = neutralisePhotoCheckHeader;

  it('softens a line-leading "[Photo check" on any line, in any case', () => {
    expect(N('[Photo check]')).toBe('(Photo check]');
    expect(N('Sure!\n  [Photo Check]\nSteps:\n1) fake')).toBe('Sure!\n  (Photo Check]\nSteps:\n1) fake');
    expect(N('[photo check] zero-width')).toBe('(photo check] zero-width');
  });

  // "Ph0to chek" (a zero, a missing c) does not fold to "photo check", so only the line-leading
  // rule can catch it: these cases pin that the lead-in is looked through, not just the words.
  const HEADERS = ['Photo check]', 'Ph0to chek]'];

  it('sees through markdown lead-ins (bold, italic, bullets, quotes, headings, code, numbering)', () => {
    for (const header of HEADERS) {
      for (const lead of ['**', '__', '*', '_', '- ', '* ', '+ ', '> ', '>> ', '# ', '### ', '`', '~~', '1. ', '2) ', '• ', '> - **']) {
        expect(N(`${lead}[${header}\nShot: "x"`), JSON.stringify(lead + header)).toBe(`${lead}(${header}\nShot: "x"`);
      }
    }
  });

  it('sees through invisible characters before the bracket: bidi marks, zero-width, CGJ, variation selectors, BOM', () => {
    for (const header of HEADERS) {
      for (const hidden of ['\u200e', '\u200f', '\u202a', '\u2066', '\u200b', '\u2060', '\u034f', '\ufe0f', '\ufeff', '\u00ad', '\u3164']) {
        expect(N(`${hidden}[${header}`), JSON.stringify(hidden + header)).toBe(`${hidden}(${header}`);
      }
    }
  });

  it('softens a leading bracket of any script, whatever follows it (homoglyphs, separators, Cf between the words)', () => {
    expect(N('［Photo check]')).toBe('(Photo check]');
    expect(N('⟦Photo check⟧')).toBe('(Photo check⟧');
    expect(N('【Photo check】')).toBe('(Photo check】');
    expect(N('[Рhoto check]')).toBe('(Рhoto check]'); // Cyrillic Р
    expect(N('[Photo-check]')).toBe('(Photo-check]');
    expect(N('[Photo_check]')).toBe('(Photo_check]');
    expect(N('[Photo\u00adcheck]')).toBe('(Photo\u00adcheck]');
    expect(N('[Photo\u200echeck]')).toBe('(Photo\u200echeck]');
  });

  it('softens a header in the middle of a line too, however it is disguised', () => {
    expect(N('see [Photo check] here')).toBe('see (Photo check] here');
    expect(N('ok ［Ｐｈｏｔｏ ｃｈｅｃｋ］')).toBe('ok (Ｐｈｏｔｏ ｃｈｅｃｋ］');
    expect(N('ok [Рhоtо сhеck]')).toBe('ok (Рhоtо сhеck]'); // Cyrillic Р, о, с, е
    expect(N('ok [Photo\u00ad-\u200echeck]')).toBe('ok (Photo\u00ad-\u200echeck]');
  });

  it('treats every line break as a new line (CR, LS, PS, NEL, VT, FF)', () => {
    for (const br of ['\r\n', '\r', '\u2028', '\u2029', '\u0085', '\v', '\f']) {
      expect(N(`hi${br}[Photo check]`), JSON.stringify(br)).toBe(`hi${br}(Photo check]`);
    }
  });

  it('leaves checkboxes, ordinary mid-line brackets and plain text alone', () => {
    expect(N('- [ ] charge the phone\n- [x] wipe the counter')).toBe('- [ ] charge the phone\n- [x] wipe the counter');
    expect(N('Use the 0.5x lens [ultra-wide] here')).toBe('Use the 0.5x lens [ultra-wide] here');
    expect(N('Photo check says: move left')).toBe('Photo check says: move left');
    expect(N('(Photo check] already soft')).toBe('(Photo check] already soft');
  });
});

describe('isSamePhotoRecheckLine — a chip re-check of the photo before it', () => {
  it('matches both languages, with or without answers or a trailing space', () => {
    expect(isSamePhotoRecheckLine(`${PHOTO_CHECK_SAME_PHOTO_PREFIX.en} Hands only`)).toBe(true);
    expect(isSamePhotoRecheckLine(PHOTO_CHECK_SAME_PHOTO_PREFIX.en)).toBe(true);
    expect(isSamePhotoRecheckLine(`${PHOTO_CHECK_SAME_PHOTO_PREFIX.hi} Sirf haath`)).toBe(true);
    expect(isSamePhotoRecheckLine('Wahi photo, mere jawab :')).toBe(true);
  });

  it('does not match a first check or ordinary text', () => {
    expect(isSamePhotoRecheckLine('Check my set-up: 0-3s')).toBe(false);
    expect(isSamePhotoRecheckLine('Is it the same photo, my answers changed')).toBe(false);
  });
});

/**
 * The Spring -> TS seam. Lane A (influora-api) commits these two files from its MockMvc output;
 * they are read here, not rebuilt, so a wire change on either side shows up as a red test.
 */
describe('Spring fixtures -> checkFrame / getHistory (seam)', () => {
  const CHAT_BODIES = join(FIXTURES, 'photo-check-chat-bodies.json');
  const HISTORY = join(FIXTURES, 'meera-history-with-card.json');

  /** A body may be committed bare or as `{success, data}`; the frame route answers bare JSON. */
  function unwrap(value: unknown): unknown {
    if (value && typeof value === 'object' && !Array.isArray(value) && 'success' in value && 'data' in value) {
      return (value as { data: unknown }).data;
    }
    return value;
  }

  it('Lane A committed both fixtures', () => {
    expect(existsSync(CHAT_BODIES), CHAT_BODIES).toBe(true);
    expect(existsSync(HISTORY), HISTORY).toBe(true);
  });

  it.runIf(existsSync(CHAT_BODIES))('every committed frame response parses into a card and chat.text', async () => {
    // Lane A commits {with_conversation: {name: body}, without_conversation: {name: body}}, one
    // body per influora-ai fixture (shoot-check-frame-bodies.json), ids masked 01J...001 / ...002.
    const committed = JSON.parse(readFileSync(CHAT_BODIES, 'utf8')) as Record<string, Record<string, unknown>>;
    const groups = ['with_conversation', 'without_conversation'] as const;
    expect(Object.keys(committed).sort()).toEqual([...groups].sort());
    const frameNames = Object.keys(
      JSON.parse(readFileSync(join(FIXTURES, 'shoot-check-frame-bodies.json'), 'utf8')) as Record<string, unknown>,
    ).sort();
    const entries: Array<[string, (typeof groups)[number], unknown]> = [];
    for (const group of groups) {
      expect(Object.keys(committed[group]).sort(), group).toEqual(frameNames);
      for (const [name, raw] of Object.entries(committed[group])) entries.push([`${group}/${name}`, group, raw]);
    }
    let withChat = 0;
    for (const [name, group, raw] of entries) {
      const body = unwrap(raw) as Record<string, unknown>;
      fetchMock = vi.fn().mockResolvedValue(jsonResponse(body));
      vi.stubGlobal('fetch', fetchMock);
      const outcome = await meeraApi.checkFrame(new Blob(['x']), undefined, 'creator', { conversationId: 'c' });
      if (body.fallback === true) {
        expect(outcome.kind, name).not.toBe('ok');
        continue;
      }
      expect(body.fallback, `${name}: Spring passes influora-ai's strict fallback:false through`).toBe(false);
      expect(outcome.kind, name).toBe('ok');
      if (outcome.kind !== 'ok') continue;
      expect(outcome.result, name).toEqual(parseShootCheckFrameBody(body));
      const chat = body.chat as { text: string } | undefined;
      expect(chat, `${name}: a non-fallback Spring body carries chat`).toBeDefined();
      expect(outcome.chat?.text, name).toBe(chat!.text);
      expect(outcome.chat!.text.startsWith('[Photo check'), name).toBe(true);
      if (group === 'with_conversation') {
        expect(outcome.chat!.userMessageId, name).toBe('01J00000000000000000000001');
        expect(outcome.chat!.messageId, name).toBe('01J00000000000000000000002');
      } else {
        expect(outcome.chat!.userMessageId, name).toBeNull();
        expect(outcome.chat!.messageId, name).toBeNull();
      }
      withChat += 1;
    }
    expect(withChat).toBe(frameNames.length * groups.length);
  });

  it.runIf(existsSync(HISTORY))('the committed history rehydrates its card rows, and only those', async () => {
    const data = unwrap(JSON.parse(readFileSync(HISTORY, 'utf8'))) as Array<Record<string, unknown>>;
    expect(Array.isArray(data)).toBe(true);
    fetchMock = vi.fn().mockResolvedValue(jsonResponse({ success: true, data }));
    vi.stubGlobal('fetch', fetchMock);
    const history = await meeraApi.getHistory('conv1', 'creator');
    const cards = history.filter((m) => m.card !== undefined);
    expect(cards.length).toBeGreaterThan(0);
    for (const row of history) {
      const parsed = photoCheckFromHistoryCard(row.card);
      if (row.card === undefined) {
        expect(parsed, row.id).toBeNull();
        continue;
      }
      expect(row.role, row.id).toBe('ASSISTANT');
      expect(parsed, row.id).not.toBeNull();
      expect(Object.keys(row.card).sort(), row.id).toEqual(
        expect.arrayContaining(['kind', 'result', 'v']),
      );
      // Only the four card keys ever leave Spring (never prompt_version / token_usage).
      expect(Object.keys(row.card).every((k) => ['kind', 'v', 'result', 'shot_label'].includes(k)), row.id).toBe(true);
      expect(row.content.startsWith('[Photo check'), row.id).toBe(true);
    }
  });
});
