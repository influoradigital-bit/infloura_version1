import { useState, type ReactNode } from 'react'
import { motion, useReducedMotion } from 'framer-motion'

import { EscrowLockSequence } from '@/components/motion/EscrowLockSequence'
import { FeeBreakdown } from '@/components/ui/fee-breakdown'
import { PayButton } from '@/components/ui/pay-button'
import { HumanStepHandoff } from '@/components/feature/meera/HumanStepHandoff'
import { MEERA_CTAS, MEERA_TRUST_COPY } from '@/data/meera-copy'
import { MEERA_EASE_ENTRY } from '@/data/motion-tokens'
import { MOCK_CAMPAIGN_PLAN, computeFee } from '@/data/meera-mock'
import { isApiLive } from '@/lib/api'
import { formatINR } from '@/lib/utils'
import { cn } from '@/lib/utils'

interface StageFundingProps {
  paid: boolean
  onPay: () => Promise<void> | void
  /** Fired only after the escrow-lock hero completes and the user explicitly goes live (B1 fix). */
  onGoLive: () => void
  /**
   * Latest `request_payment` tool_result payload for this session, if any.
   *
   * EV-024: no longer read. `request_payment` is excluded from the on-behalf
   * token scope (OnBehalfTokenService.SCOPE_DEFAULT), so this never arrives in
   * live mode, and live mode no longer renders a funding action at all. Kept on
   * the props so LivingCanvas's call site stays unchanged for whoever wires the
   * real funded-publish flow here later.
   */
  paymentToolResult?: unknown
  /** Latest `calculate_budget` tool_result payload. EV-024: no longer read — see `paymentToolResult`. */
  budgetToolResult?: unknown
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
  const live = isApiLive()
  const [lockComplete, setLockComplete] = useState(false)

  // EV-024 — in LIVE mode this stage never offers a funding action, because it
  // has none to offer. Two independent reasons, either one sufficient:
  //
  //   1. `onPay` reaches `MeeraWorkspace.handlePay`, which called
  //      `api.payments.fundEscrow(MEERA_DEMO_CAMPAIGN_ID, ...)` with the
  //      literal string 'meera_demo_campaign' — a placeholder, never a real
  //      campaign. The only outcome against a live server is an error.
  //   2. The authoritative amount rendered above the button comes from a
  //      `request_payment` tool result, and `request_payment` is DELIBERATELY
  //      excluded from the on-behalf token's scope
  //      (OnBehalfTokenService.SCOPE_DEFAULT: "The two money tools —
  //      request_payment and confirm_launch — are DELIBERATELY excluded here as
  //      defense-in-depth on the money path ... enable them via a dedicated
  //      scope only after Kabir's security review sign-off"). So the payload
  //      this branch waits for cannot arrive, and the old `else` branch left a
  //      brand staring at a "Securing your funds…" spinner forever — a
  //      progress indicator for work nobody had started.
  //
  // So: hand the step over honestly, to the control that really does it (the
  // wallet's Secure Campaign Funds card, mounted on /brand/wallet for exactly
  // this reason). Same card the chat transcript shows, from one shared module.
  //
  // MOCK mode is untouched: it exists to demonstrate the flow end to end
  // without real rails, and its fundEscrow moves nothing.
  if (live) {
    return <HumanStepHandoff step="fund" className={className} />
  }

  // Past the guard above this is MOCK mode only, so the figures are the mock
  // plan's. The live branches that used to live here (a `request_payment`
  // server amount, and a "Securing your funds…" spinner when it never came)
  // are gone with the CTA they fed — see the EV-024 note above.
  const { pool, fee, total } = computeFee(MOCK_CAMPAIGN_PLAN.pool, MOCK_CAMPAIGN_PLAN.feePercent)
  const totalLabel = formatINR(total)
  const breakdown: ReactNode = <FeeBreakdown pool={pool} fee={fee} total={total} />

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
                {MEERA_CTAS.goLive}
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
                {MEERA_CTAS.goLive}
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
