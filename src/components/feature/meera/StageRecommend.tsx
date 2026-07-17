import { StatPair } from '@/components/ui/stat-pair'
import { FeeBreakdown } from '@/components/ui/fee-breakdown'
import { StageLoadingState } from '@/components/feature/meera/StageLoadingState'
import { MEERA_STAT_LABELS } from '@/data/meera-copy'
import { MOCK_CAMPAIGN_PLAN, computeFee } from '@/data/meera-mock'
import { isApiLive } from '@/lib/api'
import { isCalculateBudgetPayload } from '@/lib/meera-api'
import { formatINR } from '@/lib/utils'
import { cn } from '@/lib/utils'

interface StageRecommendProps {
  /** Latest `calculate_budget` tool_result payload for this session, if any. */
  toolResult?: unknown
  className?: string
}

/** Stage 2 — campaign card assembles piece-by-piece: badge, count-ups, fee breakdown. */
export function StageRecommend({ toolResult, className }: StageRecommendProps) {
  const live = isApiLive()

  if (!live) {
    const { pool, fee, total } = computeFee(MOCK_CAMPAIGN_PLAN.pool, MOCK_CAMPAIGN_PLAN.feePercent)

    return (
      <div className={cn('space-y-4', className)}>
        <div className="flex flex-wrap items-center gap-2">
          <span className="rounded-full bg-meera-accent-soft px-3 py-1 text-xs font-semibold text-meera-accent">
            {MOCK_CAMPAIGN_PLAN.type}
          </span>
          <span className="rounded-full border border-meera-border bg-meera-surface-2 px-3 py-1 text-xs text-meera-text-muted">
            {MOCK_CAMPAIGN_PLAN.windowHours}-hr window
          </span>
        </div>

        <div className="grid grid-cols-2 gap-3">
          <StatPair label={MEERA_STAT_LABELS.creators} value={MOCK_CAMPAIGN_PLAN.creatorCount} formatFn={(n) => `${Math.round(n)}`} />
          <StatPair
            label={MEERA_STAT_LABELS.reach}
            value={MOCK_CAMPAIGN_PLAN.reach / 1000}
            formatFn={(n) => `${n < 10 ? n.toFixed(1) : Math.round(n)}k`}
          />
        </div>

        <FeeBreakdown pool={pool} fee={fee} total={total} />
      </div>
    )
  }

  // Live mode — calculate_budget only reports the budget numbers (no campaign
  // type/window/reach in this contract, 02 §3), so we render what we actually
  // have rather than padding the layout with fabricated stats.
  if (!isCalculateBudgetPayload(toolResult)) {
    return <StageLoadingState label="Crunching the budget…" className={className} />
  }

  return (
    <div className={cn('space-y-4', className)}>
      <StatPair
        label="Per creator"
        value={toolResult.perCreator}
        formatFn={(n) => formatINR(n)}
        className="max-w-[12rem]"
      />
      <FeeBreakdown pool={toolResult.pool} fee={toolResult.platformFee} total={toolResult.total} />
    </div>
  )
}
