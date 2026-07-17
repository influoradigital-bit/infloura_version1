import gsap from 'gsap';
import { ScrollTrigger } from 'gsap/ScrollTrigger';
import Lenis from 'lenis';

gsap.registerPlugin(ScrollTrigger);

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

export function getLenisInstance() {
  return lenisInstance;
}

export { gsap, ScrollTrigger };
