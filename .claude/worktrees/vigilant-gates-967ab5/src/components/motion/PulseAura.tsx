import { useEffect, useState } from 'react'
import { motion, useReducedMotion } from 'framer-motion'

import { MEERA_REDUCED_AURA_MS } from '@/data/motion-tokens'
import { cn } from '@/lib/utils'

interface PulseAuraProps {
  /** Increment this to re-trigger the pulse (e.g. on each payout release). */
  triggerKey: number | string
  className?: string
}

/** One-shot escrow-green aura — used at the lock moment and on payout release. */
export function PulseAura({ triggerKey, className }: PulseAuraProps) {
  const reduceMotion = useReducedMotion()
  const [reducedVisible, setReducedVisible] = useState(false)

  useEffect(() => {
    if (!reduceMotion) return
    setReducedVisible(false)
    // Mount hidden, then raise opacity on the next frame so the single
    // 150ms aura fade (spec §5 reduced-motion) is actually visible instead
    // of being a permanently-opacity-0 no-op (Priya m1).
    const frame = requestAnimationFrame(() => setReducedVisible(true))
    return () => cancelAnimationFrame(frame)
  }, [reduceMotion, triggerKey])

  if (reduceMotion) {
    return (
      <span
        key={triggerKey}
        className={cn(
          'absolute inset-0 rounded-[inherit] bg-meera-escrow-soft',
          reducedVisible ? 'opacity-60' : 'opacity-0',
          className,
        )}
        style={{ transition: `opacity ${MEERA_REDUCED_AURA_MS}ms ease-out` }}
        aria-hidden="true"
      />
    )
  }

  return (
    <motion.span
      key={triggerKey}
      className={cn('pointer-events-none absolute inset-0 rounded-[inherit] bg-meera-escrow-soft', className)}
      initial={{ opacity: 0.9, scale: 1 }}
      animate={{ opacity: 0, scale: 1.4 }}
      transition={{ duration: 0.48, ease: [0.23, 1, 0.32, 1] }}
      aria-hidden="true"
    />
  )
}
