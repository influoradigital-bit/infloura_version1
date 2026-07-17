import { CheckCircle2 } from 'lucide-react'

import { StatPair } from '@/components/ui/stat-pair'
import { SlotProgressBar } from '@/components/ui/slot-progress-bar'
import { CreatorCard } from '@/components/ui/creator-card'
import { PayoutLedger } from '@/components/feature/meera/PayoutLedger'
import { StageLoadingState } from '@/components/feature/meera/StageLoadingState'
import { MEERA_STAT_LABELS } from '@/data/meera-copy'
import { MOCK_CREATORS, MOCK_LIVE_STATS } from '@/data/meera-mock'
import { isApiLive } from '@/lib/api'
import { cn } from '@/lib/utils'

interface StageLiveProps {
  /**
   * Latest `confirm_launch` tool_result payload for this session, if any.
   * There's no documented contract for this payload (02 §3 only specs the
   * other four tools) — the go-live confirmation itself (ai.md
   * ConfirmLaunchExecutor) is what we know happened, not a dashboard-stats
   * shape, so this only gates loading vs. a plain launch confirmation.
   */
  toolResult?: unknown
  className?: string
}

/** Stage 5 — dashboard: invites count-up, slot tracker, accepted list, payout ledger. */
export function StageLive({ toolResult, className }: StageLiveProps) {
  const live = isApiLive()

  if (!live) {
    const accepted = MOCK_CREATORS.slice(0, MOCK_LIVE_STATS.slotsAccepted)

    return (
      <div className={cn('space-y-4', className)}>
        <div className="grid grid-cols-2 gap-3">
          <StatPair label={MEERA_STAT_LABELS.invitesSent} value={MOCK_LIVE_STATS.invitesSent} formatFn={(n) => `${Math.round(n)}`} />
          <StatPair label={MEERA_STAT_LABELS.slotsAccepted} value={MOCK_LIVE_STATS.slotsAccepted} formatFn={(n) => `${Math.round(n)}`} />
        </div>

        <SlotProgressBar filled={MOCK_LIVE_STATS.slotsAccepted} total={MOCK_LIVE_STATS.slotsTotal} tone="meera" />

        <div className="grid grid-cols-1 gap-2">
          {accepted.map((creator) => (
            <CreatorCard
              key={creator.id}
              name={creator.name}
              handle={creator.handle}
              city={creator.city}
              niche={creator.niche}
              followers={creator.followers}
              avatarEmoji={creator.avatarEmoji}
              verified={creator.verified}
            />
          ))}
        </div>

        <PayoutLedger />
      </div>
    )
  }

  // Live mode — `confirm_launch` has no documented dashboard-stats contract, so
  // invites/slots/payouts aren't fabricated here. Once launched, this is an
  // honest confirmation; live campaign stats belong to a real polling/detail
  // endpoint that doesn't exist yet (tracked as a known gap, not silently mocked).
  if (toolResult === undefined) {
    return <StageLoadingState label="Launching your campaign…" className={className} />
  }

  return (
    <div className={cn('flex flex-col items-center gap-3 rounded-xl border border-meera-border bg-meera-surface-2 p-8 text-center', className)}>
      <CheckCircle2 className="h-8 w-8 text-meera-escrow" aria-hidden="true" />
      <div>
        <p className="text-sm font-semibold text-meera-text">Campaign is live</p>
        <p className="mt-1 text-xs text-meera-text-muted">
          Invite and payout stats will appear here as creators respond.
        </p>
      </div>
    </div>
  )
}
