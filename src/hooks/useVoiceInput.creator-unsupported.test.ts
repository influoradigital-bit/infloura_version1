/**
 * T-MEERA-CREATOR-PHASE-A gate review fix round 2 (Priya's frontend gate, item 2).
 *
 * Vikram's CreatorMeeraController now exposes POST /creator/meera/voice/transcribe with the same
 * request/response shape as the brand route, and `meeraApi.transcribe()` (meera-api.ts) routes
 * there via the shared `basePath(role)` mapping instead of the brand-only path it used to
 * hardcode. The gate-fix-round-2 pin that reported `supported: false` for every creator — put in
 * place specifically because no creator voice route existed yet — no longer applies: this hook
 * must report real browser capability for the creator role exactly as it does for brand, so the
 * mic button renders for creators.
 *
 * (File kept at its original name — `useVoiceInput.creator-unsupported.test.ts` — per T-MEERA
 * gate review's explicit file list; its content now asserts the opposite of what its name once
 * described.)
 *
 * Run: npx vitest run src/hooks/useVoiceInput.creator-unsupported.test.ts
 */
import { renderHook } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

// vi.mock is hoisted above imports/top-level consts, so the mock fn referenced inside its
// factory must be created through vi.hoisted() — a plain top-level const here would still be
// `undefined` at the time the factory runs (see vitest's hoisting docs).
const { transcribeMock } = vi.hoisted(() => ({ transcribeMock: vi.fn().mockResolvedValue(null) }));
vi.mock('@/lib/meera-api', () => ({
  meeraApi: { transcribe: transcribeMock },
}));

import { useVoiceInput } from './useVoiceInput';

// jsdom is not a secure context and implements neither MediaRecorder nor
// webkitSpeechRecognition — stub the capability surface the hook checks so
// detectRecorderSupport()/detectBrowserSttSupport()/isSecureContext all say
// "supported" regardless of role.
beforeEach(() => {
  transcribeMock.mockClear();
  Object.defineProperty(window, 'isSecureContext', { value: true, configurable: true });
  (globalThis as unknown as { MediaRecorder: unknown }).MediaRecorder = class {
    static isTypeSupported() {
      return true;
    }
  };
  Object.defineProperty(window.navigator, 'mediaDevices', {
    value: { getUserMedia: vi.fn().mockResolvedValue({ getTracks: () => [] }) },
    configurable: true,
  });
});

// No afterEach teardown of the stubs, matching useVoiceOutput's sibling test: testing-library's
// global `cleanup()` unmounts each hook's host component afterward and the unmount effect can
// still touch these globals; unconditional re-stubbing in `beforeEach` is sufficient isolation.

describe('useVoiceInput — creator role', () => {
  it('reports supported for role "creator" now that CreatorMeeraController exposes a voice route', () => {
    const { result } = renderHook(() => useVoiceInput({ onResult: vi.fn(), role: 'creator' }));
    expect(result.current.supported).toBe(true);
  });

  it('leaves the brand role (and the default) unaffected — still reflects real capability', () => {
    const { result: withRole } = renderHook(() => useVoiceInput({ onResult: vi.fn(), role: 'brand' }));
    expect(withRole.current.supported).toBe(true);

    const { result: withDefault } = renderHook(() => useVoiceInput({ onResult: vi.fn() }));
    expect(withDefault.current.supported).toBe(true);
  });

  it('is still unsupported outside a secure context regardless of role — the fix only removed the role gate, not the real capability checks', () => {
    Object.defineProperty(window, 'isSecureContext', { value: false, configurable: true });
    const { result } = renderHook(() => useVoiceInput({ onResult: vi.fn(), role: 'creator' }));
    expect(result.current.supported).toBe(false);
  });
});
