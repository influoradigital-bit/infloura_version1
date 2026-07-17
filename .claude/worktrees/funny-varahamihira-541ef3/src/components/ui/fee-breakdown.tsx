import { cn } from '@/lib/utils'
import { formatINR } from '@/lib/utils'
import { MEERA_FEE_LABELS } from '@/data/meera-copy'

interface FeeBreakdownProps {
  pool: number
  fee: number
  total: number
  className?: string
}

/** T5 — transparent, line-itemed fee breakdown. Nothing hidden. */
export function FeeBreakdown({ pool, fee, total, className }: FeeBreakdownProps) {
  return (
    <div
      className={cn(
        'rounded-lg border border-meera-border bg-meera-surface-2 p-4 text-sm',
        className,
      )}
    >
      <dl className="space-y-2">
        <div className="flex items-center justify-between">
          <dt className="text-meera-text-muted">{MEERA_FEE_LABELS.pool}</dt>
          <dd className="font-medium tabular-nums text-meera-text">{formatINR(pool)}</dd>
        </div>
        <div className="flex items-center justify-between">
          <dt className="text-meera-text-muted">{MEERA_FEE_LABELS.fee}</dt>
          <dd className="font-medium tabular-nums text-meera-text">{formatINR(fee)}</dd>
        </div>
        <div className="flex items-center justify-between border-t border-meera-border pt-2">
          <dt className="font-semibold text-meera-text">{MEERA_FEE_LABELS.total}</dt>
          <dd className="font-semibold tabular-nums text-meera-text">{formatINR(total)}</dd>
        </div>
      </dl>
    </div>
  )
}
