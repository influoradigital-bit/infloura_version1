/**
 * T-MEERA-CREATOR-PHASE-A gate review fix round 2 (Priya's frontend gate, item 2).
 *
 * Vikram's CreatorMeeraController now exposes POST /creator/meera/voice/speak with the same
 * request/response shape as the brand route, and `meeraApi.speak()` (meera-api.ts) routes there
 * via the shared `basePath(role)` mapping instead of the brand-only path it used to hardcode. The
 * gate-fix-round-2 pin that reported `supported: false` for every creator — put in place
 * specifically because no creator voice route existed yet — no longer applies: this hook must
 * report real browser capability for the creator role exactly as it does for brand, so the
 * speaker toggle renders for creators.
 *
 * (File kept at its original name — `useVoiceOutput.creator-unsupported.test.ts` — per T-MEERA
 * gate review's explicit file list; its content now asserts the opposite of what its name once
 * described.)
 *
 * Run: npx vitest run src/hooks/useVoiceOutput.creator-unsupported.test.ts
 */
import { renderHook } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

// vi.mock is hoisted above imports/top-level consts, so the mock fn referenced inside its
// factory must be created through vi.hoisted() — a plain top-level const here would still be
// `undefined` at the time the factory runs (see vitest's hoisting docs).
const { speakMock } = vi.hoisted(() => ({ speakMock: vi.fn().mockResolvedValue(null) }));
vi.mock('@/lib/meera-api', () => ({
  meeraApi: { speak: speakMock },
}));

import { useVoiceOutput } from './useVoiceOutput';

// jsdom implements neither `speechSynthesis` nor `SpeechSynthesisUtterance` — stub both so
// `detectSupport()` says true regardless of role.
beforeEach(() => {
  speakMock.mockClear();
  (window as unknown as { speechSynthesis: unknown }).speechSynthesis = {
    getVoices: () => [],
    cancel: vi.fn(),
    speak: vi.fn(),
  };
  (globalThis as unknown as { SpeechSynthesisUtterance: unknown }).SpeechSynthesisUtterance = class {
    text: string;
    constructor(text: string) {
      this.text = text;
    }
  };
});

// Deliberately no afterEach teardown of the stub: testing-library's own global
// `cleanup()` (src/test/setup.ts) unmounts each hook's host component in its own
// afterEach, and the hook's unmount effect calls `window.speechSynthesis.cancel()`
// when `supported` — removing the stub here would race that cleanup. Re-stubbing
// unconditionally in `beforeEach` above is sufficient isolation between tests.

describe('useVoiceOutput — creator role', () => {
  it('reports supported for role "creator" now that CreatorMeeraController exposes a voice route', () => {
    const { result } = renderHook(() => useVoiceOutput('creator'));
    expect(result.current.supported).toBe(true);
  });

  it('leaves the brand role unaffected — still reflects real browser capability', () => {
    const { result } = renderHook(() => useVoiceOutput('brand'));
    expect(result.current.supported).toBe(true);
  });
});
