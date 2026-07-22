import { Link } from 'react-router-dom'
import { ArrowUpRight } from 'lucide-react'

import { StatPair } from '@/components/ui/stat-pair'
import { StageLoadingState } from '@/components/feature/meera/StageLoadingState'
import { EstimateBadge } from '@/components/feature/meera/EstimateBadge'
import { MEERA_STAT_LABELS, MEERA_PERFORMANCE_COPY } from '@/data/meera-copy'
import { MOCK_CAMPAIGN_PERFORMANCE } from '@/data/meera-mock'
import { isApiLive } from '@/lib/api'
import { isCampaignPerformancePayload } from '@/lib/meera-api'
import { cn } from '@/lib/utils'

interface StagePerformanceProps {
  /** Latest `get_campaign_performance` tool_result payload for this session, if any. */
  toolResult?: unknown
  className?: string
}

const fmtRoi = (n: number) => `${n.toFixed(1)}×`
const fmtResponseRate = (n: number) => `${Math.round(n * 100)}%`
const fmtCreatorScore = (n: number) => `${Math.round(n)}`

/**
 * Stage 6 — performance card: server-computed numbers only, no narrative.
 *
 * Card carries numbers, chat bubble carries the one-sentence Meera-voiced
 * narrative (`MessageBubble`, already rendered above this on the canvas'
 * sibling chat panel) — this component NEVER renders prose summarizing the
 * numbers and NEVER computes a derived figure itself (SR-1: `roi` etc. are
 * server fields, not `revenue / spend` done in React). See
 * `wiki/build/phase2-priya-review.md` §2 Q2 / `wiki/build/phase2-ash-review.md`
 * F3 for the ruling this design follows.
 */
export function StagePerformance({ toolResult, className }: StagePerformanceProps) {
  const live = isApiLive()

  if (!live) {
    const { roi, responseRate, avgCreatorScore } = MOCK_CAMPAIGN_PERFORMANCE

    return (
      <div className={cn('space-y-4', className)}>
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-3">
          <StatPair label={MEERA_STAT_LABELS.roi} value={roi} formatFn={fmtRoi} />
          <StatPair label={MEERA_STAT_LABELS.responseRate} value={responseRate} formatFn={fmtResponseRate} />
          <StatPair label={MEERA_STAT_LABELS.avgCreatorScore} value={avgCreatorScore} formatFn={fmtCreatorScore} />
        </div>
        <BreakdownLink />
      </div>
    )
  }

  // Live mode — `GetCampaignPerformanceResult` (Spring DTO). `roi` and
  // `provenance` are the guaranteed core; `responseRate`/`avgCreatorScore`
  // may be absent (see the payload's doc comment in lib/meera-api.ts) —
  // each tile is skipped, not fabricated, when its field is missing.
  if (!isCampaignPerformancePayload(toolResult)) {
    return <StageLoadingState label="Pulling your campaign numbers…" className={className} />
  }

  const { roi, responseRate, avgCreatorScore, provenance } = toolResult
  const hasResponseRate = typeof responseRate === 'number'
  const hasAvgCreatorScore = typeof avgCreatorScore === 'number'

  return (
    <div className={cn('space-y-4', className)}>
      <EstimateBadge provenance={provenance} />

      <div className="grid grid-cols-2 gap-3 sm:grid-cols-3">
        {roi === null ? (
          <div className="rounded-lg border border-dashed border-meera-border bg-meera-surface-2 p-3">
            <p className="text-xs text-meera-text-muted">{MEERA_STAT_LABELS.roi}</p>
            <p className="mt-1 text-sm text-meera-text-muted">{MEERA_PERFORMANCE_COPY.roiUnavailable}</p>
          </div>
        ) : (
          <StatPair label={MEERA_STAT_LABELS.roi} value={roi} formatFn={fmtRoi} />
        )}
        {hasResponseRate && (
          <StatPair label={MEERA_STAT_LABELS.responseRate} value={responseRate as number} formatFn={fmtResponseRate} />
        )}
        {hasAvgCreatorScore && (
          <StatPair label={MEERA_STAT_LABELS.avgCreatorScore} value={avgCreatorScore as number} formatFn={fmtCreatorScore} />
        )}
      </div>

      <BreakdownLink />
    </div>
  )
}

/**
 * `/brand/analytics` exists (`src/pages/brand-analytics.tsx`) but is NOT
 * campaign-scoped — flagged as a follow-up in the design doc (§7 Q6,
 * Priya/Ash F6 non-blocking). Linking to the general overview for v1 rather
 * than inventing a `/brand/analytics/campaign/:id` route that doesn't exist.
 */
function BreakdownLink() {
  return (
    <Link
      to="/brand/analytics"
      className="inline-flex items-center gap-1 text-xs font-medium text-meera-accent underline-offset-2 hover:underline"
    >
      {MEERA_PERFORMANCE_COPY.seeFullBreakdown}
      <ArrowUpRight className="h-3 w-3" aria-hidden="true" />
    </Link>
  )
}

export default StagePerformance
