import { formatINR } from '@/lib/utils'
import { MEERA_LEDGER } from '@/data/meera-copy'
import { MOCK_PAYOUT_LEDGER } from '@/data/meera-mock'
import { cn } from '@/lib/utils'

interface PayoutLedgerProps {
  className?: string
}

/** T9 — per-creator approve→release rows, a running receipt. */
export function PayoutLedger({ className }: PayoutLedgerProps) {
  return (
    <div className={cn('rounded-lg border border-meera-border bg-meera-surface p-3', className)}>
      <p className="mb-2 text-xs font-semibold uppercase tracking-wide text-meera-text-muted">
        {MEERA_LEDGER.title}
      </p>
      {MOCK_PAYOUT_LEDGER.length === 0 ? (
        <p className="text-xs text-meera-text-muted">{MEERA_LEDGER.emptyState}</p>
      ) : (
        <ul className="space-y-1.5">
          {MOCK_PAYOUT_LEDGER.map((entry) => (
            <li key={entry.id} className="flex items-center justify-between text-xs">
              <span className="text-meera-text">
                {MEERA_LEDGER.releasedLabel(entry.creatorHandle, formatINR(entry.amount))}
                <span className="ml-1 text-meera-escrow">✓</span>
              </span>
              <span className="tabular-nums text-meera-text-muted">{entry.releasedAt}</span>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
