import { Lock, ShieldAlert } from 'lucide-react'

import { cn } from '@/lib/utils'
import { MEERA_ESCROW_PILL_LABEL } from '@/data/meera-copy'

export type EscrowPillState = 'unfunded' | 'securing' | 'secured' | 'releasing'

interface EscrowPillProps {
  state: EscrowPillState
  /** Formatted amount string, e.g. formatINR(17250) — required for secured/releasing */
  amount?: string
  className?: string
}

/** T1 — persistent, always-visible money-state chip. Green only when locked/releasing. */
export function EscrowPill({ state, amount, className }: EscrowPillProps) {
  const isTrust = state === 'secured' || state === 'releasing'

  const label = (() => {
    switch (state) {
      case 'unfunded':
        return MEERA_ESCROW_PILL_LABEL.unfunded
      case 'securing':
        return MEERA_ESCROW_PILL_LABEL.securing
      case 'secured':
        return MEERA_ESCROW_PILL_LABEL.secured(amount ?? '')
      case 'releasing':
        return MEERA_ESCROW_PILL_LABEL.releasing(amount ?? '')
      default:
        return ''
    }
  })()

  const Icon = state === 'unfunded' ? ShieldAlert : state === 'securing' ? ShieldAlert : Lock

  return (
    <span
      className={cn(
        'inline-flex items-center gap-1.5 rounded-full border px-3 py-1 text-xs font-semibold tabular-nums',
        isTrust
          ? 'border-transparent bg-meera-escrow-soft text-meera-escrow'
          : 'border-meera-border bg-meera-surface-2 text-meera-text-muted',
        className,
      )}
      role="status"
      aria-label={`Escrow status: ${label}`}
    >
      <Icon className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
      {label}
    </span>
  )
}
