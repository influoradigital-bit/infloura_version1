import gsap from 'gsap';
import { ScrollTrigger } from 'gsap/ScrollTrigger';
import Lenis from 'lenis';

let scrollTriggerRegistered = false;

/**
 * F-0678 — registration is deliberately NOT at module scope.
 *
 * `gsap.registerPlugin(ScrollTrigger)` used to run on the first line of this module. That is not
 * a cheap declaration: `ScrollTrigger.register()` (node_modules/gsap/ScrollTrigger.js:1955) reads
 * `window.matchMedia` and finishes by arming a permanent poller,
 * `_syncInterval = setInterval(_sync, 250)` (ScrollTrigger.js:2115), cleared only by
 * `ScrollTrigger.disable()` (ScrollTrigger.js:1986) — which nothing in this app ever called.
 *
 * Because this module sits in `@/App`'s import graph, merely IMPORTING the app armed that 250ms
 * interval. Under vitest that interval lives in the Node timer queue and outlives jsdom's window:
 * every tick after teardown ran `_sync`, which calls a BARE `requestAnimationFrame`
 * (ScrollTrigger.js:372) that died with the window, throwing
 * `ReferenceError: requestAnimationFrame is not defined` as an unhandled error. Vitest then exited
 * 1 with every assertion green, and whether the process got out before the next tick was a race —
 * the non-determinism in F-0678.
 *
 * Deferring the call is behaviour-preserving in the browser: the `ScrollTrigger` constructor
 * self-registers (`_coreInitted || ScrollTrigger.register(gsap)`, ScrollTrigger.js:923), so
 * `useScrollPin`'s `ScrollTrigger.create(...)` works whether or not this ran first. What changes
 * is only that importing the module no longer starts a timer nobody asked for.
 *
 * Regression test: src/lib/scroll/__tests__/smooth-scroll-import-side-effect.f0678.test.ts
 */
function ensureScrollTriggerRegistered() {
  if (scrollTriggerRegistered) return;
  gsap.registerPlugin(ScrollTrigger);
  scrollTriggerRegistered = true;
}

let lenisInstance: Lenis | null = null;
let tickerBound = false;

function bindGsapTicker(lenis: Lenis) {
  if (tickerBound) return;
  gsap.ticker.add((time) => {
    lenis.raf(time * 1000);
  });
  gsap.ticker.lagSmoothing(0);
  lenis.on('scroll', ScrollTrigger.update);
  tickerBound = true;
}

export function initSmoothScroll() {
  if (lenisInstance || typeof window === 'undefined') return null;

  // The real entry point for the scroll machinery — register here, where a caller has actually
  // asked for smooth scrolling, rather than as a side effect of importing this module (F-0678).
  ensureScrollTriggerRegistered();

  lenisInstance = new Lenis({
    duration: 1.1,
    smoothWheel: true,
    touchMultiplier: 1.5,
  });

  bindGsapTicker(lenisInstance);
  return lenisInstance;
}

export function destroySmoothScroll() {
  if (!lenisInstance) return;

  lenisInstance.destroy();
  lenisInstance = null;
  ScrollTrigger.getAll().forEach((trigger) => trigger.kill());
  tickerBound = false;
}

export { gsap, ScrollTrigger };
