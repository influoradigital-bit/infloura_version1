import { useEffect, useState } from 'react'
import { motion, useReducedMotion } from 'framer-motion'
import { Check } from 'lucide-react'

import { cn } from '@/lib/utils'

interface ThinkingStateProps {
  steps: string[]
  /** ms between each step checking off */
  stepIntervalMs?: number
  className?: string
}

/** T3 — "Meera shows her work." Never a blank spinner: a streaming step log. */
export function ThinkingState({ steps, stepIntervalMs = 550, className }: ThinkingStateProps) {
  const reduceMotion = useReducedMotion()
  const [completedCount, setCompletedCount] = useState(reduceMotion ? steps.length : 0)

  useEffect(() => {
    if (reduceMotion) {
      setCompletedCount(steps.length)
      return
    }
    if (completedCount >= steps.length) return

    const timer = window.setTimeout(() => setCompletedCount((c) => c + 1), stepIntervalMs)
    return () => window.clearTimeout(timer)
  }, [completedCount, reduceMotion, stepIntervalMs, steps.length])

  return (
    <div className={cn('space-y-1.5 rounded-lg border border-meera-border bg-meera-surface-2 p-3', className)}>
      {steps.map((step, i) => {
        const done = i < completedCount
        const active = i === completedCount
        const row = (
          <div className="flex items-center gap-2 text-xs">
            <span
              className={cn(
                'flex h-4 w-4 shrink-0 items-center justify-center rounded-full border',
                done
                  ? 'border-transparent bg-meera-escrow text-white'
                  : active
                    ? 'border-meera-accent'
                    : 'border-meera-border-strong',
              )}
            >
              {done && <Check className="h-2.5 w-2.5" strokeWidth={3} />}
            </span>
            <span className={cn(done || active ? 'text-meera-text' : 'text-meera-text-muted')}>{step}</span>
          </div>
        )

        if (reduceMotion) return <div key={step}>{row}</div>

        return (
          <motion.div
            key={step}
            initial={{ opacity: 0, y: 6 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ delay: 0.15 + i * 0.1, duration: 0.25 }}
          >
            {row}
          </motion.div>
        )
      })}
    </div>
  )
}
