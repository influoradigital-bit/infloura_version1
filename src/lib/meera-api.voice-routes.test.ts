/**
 * T-MEERA-CREATOR-PHASE-A gate review fix round 2 (Priya's frontend gate, item 2).
 *
 * `meeraApi.speak()`/`transcribe()` used to hardcode the brand-only `/meera/voice/*` path for
 * every role, then short-circuited to `null` for role 'creator' as the documented workaround —
 * CreatorMeeraController exposed no voice routes at all. Vikram has since added
 * POST /creator/meera/voice/{speak,transcribe} with the same shapes as the brand routes, and
 * both methods now build their URL from the shared `basePath(role)` mapping (the same one every
 * other Meera call already uses) instead of a literal `/meera` prefix. This proves the wiring
 * directly against `fetch`, independent of the hook-level "supported" tests in
 * useVoiceInput.creator-unsupported.test.ts / useVoiceOutput.creator-unsupported.test.ts.
 *
 * Run: npx vitest run src/lib/meera-api.voice-routes.test.ts
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// isApiLive() reads VITE_API_MODE, which vitest.config.ts forces to 'mock' globally — override
// just that export so speak()/transcribe() take the live `fetch` branch under test.
vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return { ...actual, isApiLive: () => true };
});

import { meeraApi } from './meera-api';

beforeEach(() => {
  window.localStorage.clear();
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('meeraApi voice routes — role routes through basePath(role)', () => {
  it('speak() calls /creator/meera/voice/speak for role "creator"', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      headers: { get: () => 'audio/wav' },
      blob: async () => new Blob(['audio']),
    });
    vi.stubGlobal('fetch', fetchMock);

    const result = await meeraApi.speak('hello', undefined, 'creator');

    expect(result).not.toBeNull();
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url] = fetchMock.mock.calls[0] as [string];
    expect(url).toContain('/creator/meera/voice/speak');
  });

  it('speak() calls /meera/voice/speak (not /creator/meera) for role "brand"', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      headers: { get: () => 'audio/wav' },
      blob: async () => new Blob(['audio']),
    });
    vi.stubGlobal('fetch', fetchMock);

    await meeraApi.speak('hello', undefined, 'brand');

    const [url] = fetchMock.mock.calls[0] as [string];
    expect(url).not.toContain('/creator/meera');
    expect(url).toContain('/meera/voice/speak');
  });

  it('transcribe() calls /creator/meera/voice/transcribe for role "creator"', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ raw_transcript: 'hi', cleaned_text: 'hi', lang_detected: 'en-IN' }),
    });
    vi.stubGlobal('fetch', fetchMock);

    const result = await meeraApi.transcribe(new Blob(['x']), 'creator');

    expect(result).not.toBeNull();
    const [url] = fetchMock.mock.calls[0] as [string];
    expect(url).toContain('/creator/meera/voice/transcribe');
  });

  it('transcribe() calls /meera/voice/transcribe (not /creator/meera) for role "brand"', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ raw_transcript: 'hi', cleaned_text: 'hi' }),
    });
    vi.stubGlobal('fetch', fetchMock);

    await meeraApi.transcribe(new Blob(['x']), 'brand');

    const [url] = fetchMock.mock.calls[0] as [string];
    expect(url).not.toContain('/creator/meera');
    expect(url).toContain('/meera/voice/transcribe');
  });
});
