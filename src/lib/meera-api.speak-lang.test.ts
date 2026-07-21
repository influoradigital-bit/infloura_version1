/**
 * meeraApi.speak — `lang` threading (W3, Platform-AI Phase 1)
 *
 * W3 item 2: the browser must forward the Sarvam-detected `lang_detected`
 * from `/meera/voice/transcribe` as `lang` on `/meera/voice/speak`'s request
 * body, so Meera's spoken reply matches the language the user spoke
 * (voice.py reads `body.get("lang", "en-IN")`). This pins the wire contract
 * for that field: present verbatim when the caller supplies it, entirely
 * absent (not `undefined`/`null`) when it doesn't, so the backend's own
 * default kicks in.
 *
 * Runs against mocked `isApiLive`/`fetch` — vitest.config.ts force-pins
 * VITE_API_MODE=mock for the whole suite, so `isApiLive` is stubbed true
 * here to exercise the live-mode fetch path.
 */
import { afterEach, describe, expect, it, vi } from 'vitest';

vi.mock('@/lib/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/api')>();
  return { ...actual, isApiLive: () => true };
});

import { meeraApi } from '@/lib/meera-api';

function audioResponse(): Response {
  return new Response(new Blob(['audio-bytes'], { type: 'audio/wav' }), {
    status: 200,
    headers: { 'Content-Type': 'audio/wav' },
  });
}

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe('meeraApi.speak (lang param)', () => {
  it('includes `lang` in the POST body when the caller supplies a detected language', async () => {
    const fetchMock = vi.fn().mockResolvedValue(audioResponse());
    vi.stubGlobal('fetch', fetchMock);

    const blob = await meeraApi.speak('Namaste, kaise ho?', 'hi-IN');

    expect(blob).not.toBeNull();
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toContain('/meera/voice/speak');
    expect(JSON.parse(init.body as string)).toEqual({
      text: 'Namaste, kaise ho?',
      lang: 'hi-IN',
    });
  });

  it('omits `lang` entirely when the caller has no detection, so the backend default (en-IN) applies', async () => {
    const fetchMock = vi.fn().mockResolvedValue(audioResponse());
    vi.stubGlobal('fetch', fetchMock);

    await meeraApi.speak('Hello there');

    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    const body = JSON.parse(init.body as string);
    expect(body).toEqual({ text: 'Hello there' });
    expect(body.lang).toBeUndefined();
  });
});
