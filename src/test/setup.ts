/**
 * Vitest Test Setup
 * Owner: Kavya (QA Lead)
 *
 * Global test setup for vitest. Extends expect with @testing-library/jest-dom
 * matchers (toBeInTheDocument, toHaveClass, etc.) and provides cleanup after
 * each test.
 */

import { afterEach } from 'vitest';
import { cleanup, configure } from '@testing-library/react';
// The /vitest subpath both registers the jest-dom matchers on vitest's `expect`
// AND augments vitest's `Assertion` types (toBeInTheDocument, toHaveClass, …) so
// `tsc --noEmit` type-checks .test.tsx files instead of erroring on every matcher.
import '@testing-library/jest-dom/vitest';

// jsdom ships no ResizeObserver, but several Radix primitives (@radix-ui/react-use-size, used by
// Checkbox/Select/Popover internals) construct one on mount — so any page test that renders one
// of those dies with "ResizeObserver is not defined" before a single assertion runs. A no-op stub
// is the right shape here: nothing under test asserts on observed sizes, it only needs the
// constructor to exist. Only defined when absent, so a real implementation (or a per-test spy)
// always wins.
// [F-0678] READ THIS BEFORE TRUSTING THE BLOCK BELOW: it does NOT fix F-0678, and under this
// project's config it never executes at all.
//
// The original note here claimed this shim closed F-0678 (`npm test` exiting 1 with every
// assertion green). It did not, for a reason the guard makes plain: vitest's jsdom environment
// defaults to `pretendToBeVisual: true`, and jsdom-with-pretendToBeVisual DEFINES
// `requestAnimationFrame`. In a jsdom test `globalThis` IS the jsdom window, so
// `typeof globalThis.requestAnimationFrame` is `'function'`, the condition is false, and the
// assignment never runs. Every test file in this repo uses the jsdom environment (there is no
// `@vitest-environment node` anywhere in src/), so this is dead code today — which is exactly
// why adding it did not change the observed failure rate.
//
// The real cause was an import-time side effect, and it is fixed at its source in
// `src/lib/scroll/smooth-scroll.ts`: `gsap.registerPlugin(ScrollTrigger)` used to run at MODULE
// SCOPE, and `ScrollTrigger.register()` arms a permanent `setInterval(_sync, 250)`
// (ScrollTrigger.js:2115) — an INTERVAL, not the `setTimeout` the old note described. That timer
// lives in the Node queue, outlives jsdom's window, and each post-teardown tick calls a bare
// `requestAnimationFrame` (ScrollTrigger.js:372) that died with the window. Registration is now
// deferred into `initSmoothScroll()`, so importing `@/App` arms nothing.
// Proof: src/lib/scroll/__tests__/smooth-scroll-import-side-effect.f0678.test.ts
//
// The block is kept only as a genuine safety net for a future `@vitest-environment node` test,
// where jsdom's rAF really would be absent. It is not load-bearing for F-0678 — do not read a
// green suite as evidence that it works.
if (typeof globalThis.requestAnimationFrame === 'undefined') {
  globalThis.requestAnimationFrame = ((cb: FrameRequestCallback) =>
    setTimeout(() => cb(Date.now()), 0) as unknown as number) as typeof requestAnimationFrame;
}
if (typeof globalThis.cancelAnimationFrame === 'undefined') {
  globalThis.cancelAnimationFrame = ((handle: number) =>
    clearTimeout(handle as unknown as NodeJS.Timeout)) as typeof cancelAnimationFrame;
}

if (typeof globalThis.ResizeObserver === 'undefined') {
  globalThis.ResizeObserver = class {
    observe() {}
    unobserve() {}
    disconnect() {}
  } as unknown as typeof ResizeObserver;
}

// Same class of jsdom gap, needed one layer further in: Radix Select/Dropdown guard their open
// logic on the Pointer Capture API and scroll the highlighted option into view. jsdom implements
// none of these, so `userEvent.click(combobox)` silently fails to open the listbox and any test
// that picks an option can never find it. No-op stubs restore the interaction without changing
// behaviour under test — nothing here asserts on capture state or scroll position.
for (const method of ['hasPointerCapture', 'setPointerCapture', 'releasePointerCapture'] as const) {
  if (!(method in Element.prototype)) {
    Object.defineProperty(Element.prototype, method, {
      value: method === 'hasPointerCapture' ? () => false : () => {},
      writable: true,
      configurable: true,
    });
  }
}
if (!('scrollIntoView' in Element.prototype)) {
  Object.defineProperty(Element.prototype, 'scrollIntoView', {
    value: () => {},
    writable: true,
    configurable: true,
  });
}

/**
 * F-0217 — the suite was non-deterministic under load.
 *
 * Testing Library's async helpers (`findBy*`, `waitFor`, `waitForElementToBeRemoved`) have their
 * OWN timeout, defaulting to 1000ms. Vitest's `testTimeout: 15_000` does not govern it, so on a
 * busy machine — a shared CI runner, or a laptop also running a build — a page test that renders
 * Radix + a router + a mocked fetch could still be mounting when the 1s window closed. The
 * failures looked like real assertion failures ("Unable to find an accessible element with the
 * role button"), which is why they were repeatedly mistaken for genuine breakage.
 *
 * Measured: three concurrent full-suite runs failed 14, 8 and 8 tests, a different combination
 * each time, with every one passing standalone.
 *
 * 5s is a deliberate trade. It does not weaken any assertion — an element that never appears
 * still fails, just later. What it removes is the class of failure where the only difference
 * between green and red was how busy the CPU happened to be. Individual call sites can still pass
 * a shorter timeout where the *speed* of an appearance is the thing under test.
 */
configure({ asyncUtilTimeout: 5_000 });

/**
 * F-0217, second half — write `userEvent.setup({ delay: null })`, not `userEvent.setup()`.
 *
 * user-event inserts a real delay between every keystroke and click by default, so typing an
 * email address costs ~20 macrotasks plus a React render each. That is where the heavy page
 * suites actually spent their time: the change-password file alone dropped from 5.18s to 2.72s
 * of test time (-47%) purely from this. Slow tests are what turn CPU contention into a timeout,
 * so this matters more than any timeout ceiling.
 *
 * `delay: null` removes the artificial wait only. user-event still wraps every event in `act()`,
 * so React state updates are flushed exactly as before — nothing is skipped, and no assertion
 * becomes weaker. There is no global default for this in user-event v14, which is why it is a
 * convention enforced by copy rather than a setting here.
 */

// Cleanup after each test (unmount React components)
afterEach(() => {
  cleanup();
});
