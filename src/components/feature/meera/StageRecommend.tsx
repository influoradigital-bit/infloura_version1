import { StatPair } from '@/components/ui/stat-pair'
import { FeeBreakdown } from '@/components/ui/fee-breakdown'
import { StageLoadingState } from '@/components/feature/meera/StageLoadingState'
import { MEERA_STAT_LABELS } from '@/data/meera-copy'
import { MOCK_CAMPAIGN_PLAN, computeFee } from '@/data/meera-mock'
import { isApiLive } from '@/lib/api'
import { isCalculateBudgetPayload, isQuotedBudget } from '@/lib/meera-api'
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

  // Live mode — `CalculateBudgetResult` (Spring DTO) is an advisory suggestion:
  // suggestedPoolTotal / suggestedPerCreatorRate / suggestedCreatorCount /
  // currency / rationale. There's no pool/fee/total split on this DTO, so we
  // show exactly what it provides rather than inventing a platformFee.
  if (!isCalculateBudgetPayload(toolResult)) {
    return <StageLoadingState label="Crunching the budget…" className={className} />
  }

  // P1-12: when there is no real niche rate band, the DTO carries NO money fields at all and this
  // canvas must not render currency. Previously the two tiles below showed a percentage of the
  // product price (₹318/creator for a ₹5,300 product), which is the number a brand acts on.
  // Saying we don't know is the honest state; the ask lives in Meera's message beside this.
  if (!isQuotedBudget(toolResult)) {
    return (
      <div className={cn('space-y-4', className)}>
        <p className="rounded-lg border border-meera-border bg-meera-surface-2 p-3 text-xs text-meera-text-muted">
          We don&rsquo;t have enough completed collaborations in your niche yet to know what
          creators actually charge, so we&rsquo;re not going to guess a rate. Tell Meera what you
          usually pay a creator &mdash; or your total budget &mdash; and she&rsquo;ll plan around it.
        </p>
        <StatPair
          label="Suggested creators"
          value={toolResult.suggestedCreatorCount}
          formatFn={(n) => `${Math.round(n)}`}
          className="max-w-[12rem]"
        />
      </div>
    )
  }

  return (
    <div className={cn('space-y-4', className)}>
      <div className="grid grid-cols-2 gap-3">
        <StatPair label="Suggested pool" value={toolResult.suggestedPoolTotal} formatFn={(n) => formatINR(n)} />
        <StatPair
          label="Per creator (whole collab)"
          value={toolResult.suggestedPerCreatorRate}
          formatFn={(n) => formatINR(n)}
        />
      </div>
      <StatPair
        label="Suggested creators"
        value={toolResult.suggestedCreatorCount}
        formatFn={(n) => `${Math.round(n)}`}
        className="max-w-[12rem]"
      />
      {/* P1-12: `rationale` is NOT rendered here any more. It is written for Meera, not for the
          brand — it contains directives ("Quote it as a RANGE, not a fixed price", "NO RATE
          QUOTED, do not invent one"). Showing Meera's instructions to the brand reads as
          machinery leaking through the product. The basis line below says the same thing in the
          brand's language. */}
      <p className="rounded-lg border border-meera-border bg-meera-surface-2 p-3 text-xs text-meera-text-muted">
        Median of real agreed rates from
        {typeof toolResult.rateSampleSize === 'number' ? ` ${toolResult.rateSampleSize}` : ''}{' '}
        creators&rsquo; completed collaborations
        {toolResult.rateNiche ? ` in ${toolResult.rateNiche}` : ''}
        {typeof toolResult.perCreatorRateMin === 'number' &&
        typeof toolResult.perCreatorRateMax === 'number'
          ? ` (typically ${formatINR(toolResult.perCreatorRateMin)}–${formatINR(toolResult.perCreatorRateMax)} per creator)`
          : ''}
        . This covers the whole collaboration per creator, not a single reel. Advisory &mdash; the
        amount charged at funding is always recalculated.
      </p>
    </div>
  )
}
