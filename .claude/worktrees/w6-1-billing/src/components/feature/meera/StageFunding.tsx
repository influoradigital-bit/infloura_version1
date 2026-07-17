import { useState } from 'react'
import { motion, useReducedMotion } from 'framer-motion'

import { EscrowLockSequence } from '@/components/motion/EscrowLockSequence'
import { FeeBreakdown } from '@/components/ui/fee-breakdown'
import { PayButton } from '@/components/ui/pay-button'
import { MEERA_CTAS, MEERA_TRUST_COPY } from '@/data/meera-copy'
import { MEERA_EASE_ENTRY } from '@/data/motion-tokens'
import { MOCK_CAMPAIGN_PLAN, computeFee } from '@/data/meera-mock'
import { formatINR } from '@/lib/utils'
import { cn } from '@/lib/utils'

interface StageFundingProps {
  paid: boolean
  onPay: () => Promise<void> | void
  /** Fired only after the escrow-lock hero completes and the user explicitly goes live (B1 fix). */
  onGoLive: () => void
  className?: string
}

/**
 * Stage 4 — escrow-lock hero (T2) on Razorpay success; fee breakdown persists.
 * B1 fix (Priya sign-off): staying `paid` no longer auto-advances the stage.
 * The lock sequence plays to completion here, then reveals an explicit
 * "Approve & release" CTA that is the only thing that calls `onGoLive`.
 */
export function StageFunding({ paid, onPay, onGoLive, className }: StageFundingProps) {
  const reduceMotion = useReducedMotion()
  const { pool, fee, total } = computeFee(MOCK_CAMPAIGN_PLAN.pool, MOCK_CAMPAIGN_PLAN.feePercent)
  const totalLabel = formatINR(total)
  const [lockComplete, setLockComplete] = useState(false)

  return (
    <div className={cn('space-y-4', className)}>
      <FeeBreakdown pool={pool} fee={fee} total={total} />

      {paid ? (
        <div className="space-y-4">
          <EscrowLockSequence amountLabel={totalLabel} onComplete={() => setLockComplete(true)} />

          {lockComplete &&
            (reduceMotion ? (
              <button
                type="button"
                onClick={onGoLive}
                className="inline-flex h-11 w-full items-center justify-center rounded-lg bg-meera-accent px-6 text-sm font-semibold text-white transition-colors duration-150 ease-out hover:bg-meera-accent-hover active:scale-[0.97] focus-visible:outline-none focus-visible:ring-4 focus-visible:ring-[var(--meera-accent-glow)]"
              >
                {MEERA_CTAS.approveAndRelease}
              </button>
            ) : (
              <motion.button
                type="button"
                onClick={onGoLive}
                initial={{ opacity: 0, y: 12 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ duration: 0.4, ease: MEERA_EASE_ENTRY }}
                className="inline-flex h-11 w-full items-center justify-center rounded-lg bg-meera-accent px-6 text-sm font-semibold text-white transition-colors duration-150 ease-out hover:bg-meera-accent-hover active:scale-[0.97] focus-visible:outline-none focus-visible:ring-4 focus-visible:ring-[var(--meera-accent-glow)]"
              >
                {MEERA_CTAS.approveAndRelease}
              </motion.button>
            ))}
        </div>
      ) : (
        <div className="space-y-3">
          <p className="text-center text-xs text-meera-text-muted">{MEERA_TRUST_COPY.releaseNote}</p>
          <PayButton label={MEERA_CTAS.fundAndGoLive(totalLabel)} onPay={onPay} />
        </div>
      )}
    </div>
  )
}
