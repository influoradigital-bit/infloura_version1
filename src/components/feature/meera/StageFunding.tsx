import { useState, type ReactNode } from 'react'
import { motion, useReducedMotion } from 'framer-motion'

import { EscrowLockSequence } from '@/components/motion/EscrowLockSequence'
import { FeeBreakdown } from '@/components/ui/fee-breakdown'
import { PayButton } from '@/components/ui/pay-button'
import { StageLoadingState } from '@/components/feature/meera/StageLoadingState'
import { MEERA_CTAS, MEERA_TRUST_COPY } from '@/data/meera-copy'
import { MEERA_EASE_ENTRY } from '@/data/motion-tokens'
import { MOCK_CAMPAIGN_PLAN, computeFee } from '@/data/meera-mock'
import { isApiLive } from '@/lib/api'
import { isCalculateBudgetPayload, isRequestPaymentPayload } from '@/lib/meera-api'
import { formatINR } from '@/lib/utils'
import { cn } from '@/lib/utils'

interface StageFundingProps {
  paid: boolean
  onPay: () => Promise<void> | void
  /** Fired only after the escrow-lock hero completes and the user explicitly goes live (B1 fix). */
  onGoLive: () => void
  /** Latest `request_payment` tool_result payload for this session, if any — the funding stage's own trigger. */
  paymentToolResult?: unknown
  /** Latest `calculate_budget` tool_result payload, carried forward from the recommend stage for the pool/fee line items. */
  budgetToolResult?: unknown
  className?: string
}

/**
 * Stage 4 — escrow-lock hero (T2) on Razorpay success; fee breakdown persists.
 * B1 fix (Priya sign-off): staying `paid` no longer auto-advances the stage.
 * The lock sequence plays to completion here, then reveals an explicit
 * "Approve & release" CTA that is the only thing that calls `onGoLive`.
 */
export function StageFunding({ paid, onPay, onGoLive, paymentToolResult, budgetToolResult, className }: StageFundingProps) {
  const reduceMotion = useReducedMotion()
  const live = isApiLive()
  const [lockComplete, setLockComplete] = useState(false)

  let totalLabel: string
  let breakdown: ReactNode

  if (!live) {
    const { pool, fee, total } = computeFee(MOCK_CAMPAIGN_PLAN.pool, MOCK_CAMPAIGN_PLAN.feePercent)
    totalLabel = formatINR(total)
    breakdown = <FeeBreakdown pool={pool} fee={fee} total={total} />
  } else if (isRequestPaymentPayload(paymentToolResult)) {
    // `serverAmount` is server-confirmed (money safety — never trust a client-derived
    // total); the pool/fee line items, if we still have them from the recommend stage,
    // are display-only context and never override it.
    totalLabel = formatINR(paymentToolResult.serverAmount)
    breakdown = isCalculateBudgetPayload(budgetToolResult) ? (
      <FeeBreakdown pool={budgetToolResult.pool} fee={budgetToolResult.platformFee} total={paymentToolResult.serverAmount} />
    ) : (
      <div className="rounded-lg border border-meera-border bg-meera-surface-2 p-4 text-sm">
        <div className="flex items-center justify-between">
          <span className="font-semibold text-meera-text">Total to fund</span>
          <span className="font-semibold tabular-nums text-meera-text">{totalLabel}</span>
        </div>
      </div>
    )
  } else {
    return <StageLoadingState label="Preparing your escrow…" className={className} />
  }

  return (
    <div className={cn('space-y-4', className)}>
      {breakdown}

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
