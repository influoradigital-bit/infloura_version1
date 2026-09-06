/**
 * F-0678 — `npm test` exits 1 non-deterministically while every assertion passes.
 *
 * ROOT CAUSE, proven by this file: `src/lib/scroll/smooth-scroll.ts` called
 * `gsap.registerPlugin(ScrollTrigger)` at MODULE SCOPE. `ScrollTrigger.register()`
 * (node_modules/gsap/ScrollTrigger.js:1955) ends by arming a permanent polling timer,
 * `_syncInterval = setInterval(_sync, 250)` (ScrollTrigger.js:2115), which is cleared only by
 * `ScrollTrigger.disable()` (ScrollTrigger.js:1986) — something neither the app nor the suite
 * ever called.
 *
 * So merely IMPORTING anything that reaches `@/App` armed a 250ms interval in the NODE timer
 * queue, which outlives jsdom's window. After teardown each tick runs `_sync`, which calls a
 * BARE `requestAnimationFrame` (ScrollTrigger.js:372); jsdom's `requestAnimationFrame` died with
 * the window, so it throws `ReferenceError: requestAnimationFrame is not defined` as an
 * UNHANDLED error and vitest exits 1 with every assertion green. Whether the process exits
 * before the next 250ms tick is a race — hence the non-determinism.
 *
 * `src/__tests__/creator-protected-route.test.tsx` is the file that surfaced it: it is the only
 * test that imports `@/App` with `window.matchMedia` stubbed, so it is the only one where
 * `register()` runs to completion and the interval actually arms. This test therefore stubs
 * `matchMedia` the same way — without it `register()` throws at ScrollTrigger.js:2070, short of
 * the `setInterval` on 2115, and the test would pass for the wrong reason.
 *
 * The assertion is on the SIDE EFFECT OF THE REAL IMPORT — a real dynamic `import()` of the real
 * module, no reimplementation. It is red against the module-scope `registerPlugin` and green once
 * registration is deferred into `initSmoothScroll()`.
 *
 * Deferring is behaviour-preserving: the `ScrollTrigger` constructor self-registers
 * (`_coreInitted || ScrollTrigger.register(gsap)`, ScrollTrigger.js:923), so `useScrollPin`'s
 * `ScrollTrigger.create(...)` and every other genuine use still work untouched.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

function stubMatchMedia() {
  // Same stub creator-protected-route.test.tsx installs. Required for `register()` to get PAST
  // ScrollTrigger.js:2070 and reach the `setInterval` on 2115 — i.e. to reproduce the real
  // conditions under which the interval was armed.
  window.matchMedia = ((query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => false,
  })) as unknown as typeof window.matchMedia;
}

describe('smooth-scroll — F-0678: importing the module must arm no timer', () => {
  beforeEach(() => {
    vi.resetModules();
    stubMatchMedia();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('arms no repeating timer at import time', async () => {
    const setIntervalSpy = vi.spyOn(globalThis, 'setInterval');

    await import('@/lib/scroll/smooth-scroll');

    // 250 is ScrollTrigger's `_syncInterval` (ScrollTrigger.js:2115) — the specific timer whose
    // post-teardown tick throws the bare-`requestAnimationFrame` ReferenceError. Asserted by
    // delay rather than as "no intervals at all" because importing `gsap` core legitimately arms
    // its own ~16.67ms ticker fallback, which is not this defect and is not what this fix removes.
    expect(setIntervalSpy.mock.calls.map((call) => call[1])).not.toContain(250);
  });

  it('does not run ScrollTrigger.register() at import time', async () => {
    // `ScrollTrigger.register()` reaches `window.matchMedia` on its way to arming the interval —
    // that is the very call that used to throw `_win.matchMedia is not a function` when this
    // module was imported under a jsdom test with no stub installed. So an unused matchMedia spy
    // is direct evidence that register() never ran, and it needs no cast to observe.
    const matchMediaSpy = vi.spyOn(window, 'matchMedia');

    await import('@/lib/scroll/smooth-scroll');

    expect(matchMediaSpy).not.toHaveBeenCalled();
  });
});
