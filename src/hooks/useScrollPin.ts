import { useGSAP } from '@gsap/react';
import { useReducedMotion } from 'framer-motion';
import type { RefObject } from 'react';

import { gsap, ScrollTrigger } from '@/lib/scroll/smooth-scroll';

type ScrollPinOptions = {
  enabled?: boolean;
  pinSpacing?: boolean;
  end?: string;
};

/** GSAP ScrollTrigger pin — Phase 7 pinned sections. Skipped when reduced motion is on. */
export function useScrollPin(
  containerRef: RefObject<HTMLElement | null>,
  targetRef: RefObject<HTMLElement | null>,
  options: ScrollPinOptions = {},
) {
  const reduceMotion = useReducedMotion();
  const { enabled = true, pinSpacing = true, end = '+=600' } = options;

  useGSAP(
    () => {
      if (!enabled || reduceMotion || !containerRef.current || !targetRef.current) return;

      ScrollTrigger.create({
        trigger: containerRef.current,
        pin: targetRef.current,
        pinSpacing,
        start: 'top top',
        end,
        anticipatePin: 1,
      });

      return () => {
        ScrollTrigger.getAll().forEach((trigger) => trigger.kill());
      };
    },
    { scope: containerRef, dependencies: [enabled, reduceMotion, end, pinSpacing] },
  );
}

export { gsap, ScrollTrigger };
