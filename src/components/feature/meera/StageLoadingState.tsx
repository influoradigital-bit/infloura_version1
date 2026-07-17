import { Loader2 } from 'lucide-react'

import { cn } from '@/lib/utils'

interface StageLoadingStateProps {
  label: string
  className?: string
}

/**
 * Shared live-mode placeholder for a Living Canvas stage that's active but
 * has no tool_result payload yet. Honesty-over-fabrication (coding
 * guidelines, cross-cutting) — live mode never falls back to mock data, so
 * this is what renders instead while waiting on the real backend.
 */
export function StageLoadingState({ label, className }: StageLoadingStateProps) {
  return (
    <div
      role="status"
      className={cn(
        'flex min-h-[8rem] items-center justify-center gap-2 rounded-xl border border-dashed border-meera-border bg-meera-surface-2 p-8 text-center text-sm text-meera-text-muted',
        className,
      )}
    >
      <Loader2 className="h-4 w-4 shrink-0 animate-spin" aria-hidden="true" />
      <span>{label}</span>
    </div>
  )
}

export default StageLoadingState
