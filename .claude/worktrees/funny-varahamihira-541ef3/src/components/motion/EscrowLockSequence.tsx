import { useEffect, useState } from 'react'
import { motion, useReducedMotion } from 'framer-motion'

import { LockFallback } from '@/components/3d/LockFallback'
import { PulseAura } from '@/components/motion/PulseAura'
import { MEERA_EASE_ENTRY, MEERA_LOCK_TIMELINE } from '@/data/motion-tokens'
import { MEERA_TRUST_COPY } from '@/data/meera-copy'
import { cn } from '@/lib/utils'

interface EscrowLockSequenceProps {
  /** Formatted amount, e.g. formatINR(17250) */
  amountLabel: string
  /** Fired once the full fill -> lock -> pulse -> caption timeline has played. */
  onComplete?: () => void
  className?: string
}

/**
 * T2 — the signature escrow-lock hero moment. Event-triggered timeline
 * (fill → lock → pulse → caption), NOT the scroll-pinned EscrowFlowAnimation.
 * Reuses that component's reduced-motion handling pattern and staged-reveal
 * shape, but this is a new timeline component per handoff §4/§8.6.
 *
 * SVG lock (LockFallback) is the default render — no WebGL required to land
 * this moment. Runs once on mount (Stage 4 entry after payment).
 */
export function EscrowLockSequence({ amountLabel, onComplete, className }: EscrowLockSequenceProps) {
  const reduceMotion = useReducedMotion()
  const [locked, setLocked] = useState(!!reduceMotion)
  const [showCaption, setShowCaption] = useState(!!reduceMotion)

  useEffect(() => {
    if (reduceMotion) {
      // Reduced motion still renders the final state immediately — still let
      // the "go live" affordance appear right away rather than never firing.
      const completeTimer = window.setTimeout(() => onComplete?.(), 0)
      return () => window.clearTimeout(completeTimer)
    }

    const lockTimer = window.setTimeout(() => setLocked(true), MEERA_LOCK_TIMELINE.meterFillMs)
    const captionTimer = window.setTimeout(
      () => setShowCaption(true),
      MEERA_LOCK_TIMELINE.captionDelayMs,
    )
    const completeTimer = window.setTimeout(() => onComplete?.(), MEERA_LOCK_TIMELINE.totalMs)

    return () => {
      window.clearTimeout(lockTimer)
      window.clearTimeout(captionTimer)
      window.clearTimeout(completeTimer)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [reduceMotion])

  return (
    <div className={cn('flex flex-col items-center gap-6 py-8 text-center', className)}>
      {/* Meter fill */}
      <div className="h-2 w-full max-w-xs overflow-hidden rounded-full bg-meera-surface-2" aria-hidden="true">
        <motion.div
          className="h-full rounded-full bg-meera-escrow"
          initial={{ width: reduceMotion ? '100%' : '0%' }}
          animate={{ width: '100%' }}
          transition={{ duration: reduceMotion ? 0 : MEERA_LOCK_TIMELINE.meterFillMs / 1000, ease: 'easeOut' }}
        />
      </div>

      {/* Lock + aura */}
      <div className="relative flex items-center justify-center">
        <LockFallback locked={locked} className="relative z-10" />
        {locked && <PulseAura triggerKey="lock" className="z-0" />}
      </div>

      {/* Caption (T7) — plain motion.p (not FadeUp) so this signature moment
          doesn't ride a component with a red tsc path (Priya M4). */}
      {showCaption &&
        (reduceMotion ? (
          <p className="max-w-xs text-base font-semibold text-meera-text">
            {MEERA_TRUST_COPY.lockCaption(amountLabel)}
          </p>
        ) : (
          <motion.p
            className="max-w-xs text-base font-semibold text-meera-text"
            initial={{ opacity: 0, y: 12 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.4, ease: MEERA_EASE_ENTRY }}
          >
            {MEERA_TRUST_COPY.lockCaption(amountLabel)}
          </motion.p>
        ))}
    </div>
  )
}
