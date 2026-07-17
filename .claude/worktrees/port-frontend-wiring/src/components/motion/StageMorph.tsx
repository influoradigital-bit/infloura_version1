import { AnimatePresence, motion, useReducedMotion } from 'framer-motion'
import type { ReactNode } from 'react'

import {
  MEERA_STAGE_EXIT,
  MEERA_STAGE_ENTER_FROM,
  MEERA_STAGE_ENTER_TO,
  MEERA_STAGE_EXIT_DURATION,
} from '@/data/motion-tokens'
import { DURATION_NORMAL, EASE_OUT } from '@/lib/motion-config'
import { cn } from '@/lib/utils'

interface StageMorphProps {
  /** Unique key for the current stage body — swaps trigger the morph. */
  stageKey: string
  children: ReactNode
  className?: string
}

/**
 * AnimatePresence mode="wait" wrapper for Living Canvas stage swaps.
 * Header stays mounted outside this component; only the body morphs.
 * Exit is quick (~180ms per spec §5), entry uses the standard FadeUp timing.
 * Reduced motion → instant swap, no exit/enter animation.
 */
export function StageMorph({ stageKey, children, className }: StageMorphProps) {
  const reduceMotion = useReducedMotion()

  if (reduceMotion) {
    return <div className={cn(className)}>{children}</div>
  }

  return (
    <AnimatePresence mode="wait">
      <motion.div
        key={stageKey}
        className={cn(className)}
        initial={MEERA_STAGE_ENTER_FROM}
        animate={MEERA_STAGE_ENTER_TO}
        exit={{ ...MEERA_STAGE_EXIT, transition: { duration: MEERA_STAGE_EXIT_DURATION } }}
        transition={{ duration: DURATION_NORMAL, ease: EASE_OUT }}
      >
        {children}
      </motion.div>
    </AnimatePresence>
  )
}
