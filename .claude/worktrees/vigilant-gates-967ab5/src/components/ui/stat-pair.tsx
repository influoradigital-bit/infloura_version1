import { CountUp } from '@/components/motion/CountUp'
import { cn } from '@/lib/utils'

interface StatPairProps {
  label: string
  value: number
  prefix?: string
  suffix?: string
  formatFn?: (n: number) => string
  className?: string
}

/** T6 — label + CountUp tile. Reuses the existing CountUp as-is (reduced-motion safe). */
export function StatPair({ label, value, prefix, suffix, formatFn, className }: StatPairProps) {
  return (
    <div className={cn('rounded-lg border border-meera-border bg-meera-surface-2 p-3', className)}>
      <p className="text-xs text-meera-text-muted">{label}</p>
      <CountUp
        value={value}
        prefix={prefix}
        suffix={suffix}
        formatFn={formatFn}
        className="mt-1 block text-xl font-semibold tabular-nums text-meera-text"
      />
    </div>
  )
}
