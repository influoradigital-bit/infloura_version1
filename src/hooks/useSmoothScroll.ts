import { useReducedMotion } from 'framer-motion';
import { useEffect } from 'react';

import { destroySmoothScroll, initSmoothScroll } from '@/lib/scroll/smooth-scroll';

/** Lenis smooth scroll — marketing/static pages only. Disabled when reduced motion is on. */
export function useSmoothScroll(enabled = true) {
  const reduceMotion = useReducedMotion();

  useEffect(() => {
    if (!enabled || reduceMotion) {
      destroySmoothScroll();
      return;
    }

    initSmoothScroll();

    return () => {
      destroySmoothScroll();
    };
  }, [enabled, reduceMotion]);
}
