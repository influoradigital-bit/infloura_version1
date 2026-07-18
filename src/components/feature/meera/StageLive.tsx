import { CheckCircle2 } from 'lucide-react'

import { StatPair } from '@/components/ui/stat-pair'
import { SlotProgressBar } from '@/components/ui/slot-progress-bar'
import { CreatorCard } from '@/components/ui/creator-card'
import { PayoutLedger } from '@/components/feature/meera/PayoutLedger'
import { StageLoadingState } from '@/components/feature/meera/StageLoadingState'
import { MEERA_STAT_LABELS } from '@/data/meera-copy'
import { MOCK_CREATORS, MOCK_LIVE_STATS } from '@/data/meera-mock'
import { isApiLive } from '@/lib/api'
import { isConfirmLaunchPayload } from '@/lib/meera-api'
import { cn } from '@/lib/utils'

interface StageLiveProps {
  /**
   * Latest `confirm_launch` tool_result payload for this session, if any.
   * `MeeraToolDtos.ConfirmLaunchResult` gives us `{campaignId, status,
   * creatorsInvited, replay}` — a go-live confirmation, not a live
   * invites-accepted/payout-ledger dashboard feed, so this only unlocks an
   * honest confirmation card (with the real invited count when present),
   * never the mock's full stats grid.
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

  // Live mode — `confirm_launch`'s own result isn't a live invites-accepted/
  // payout dashboard feed, so slot progress + payouts aren't fabricated here.
  // Once launched, this is an honest confirmation (with the real invited
  // count when the DTO has one); accepted-slots/payout-ledger data belongs to
  // a real polling/detail endpoint that doesn't exist yet (known gap, not
  // silently mocked).
  if (!isConfirmLaunchPayload(toolResult)) {
    return <StageLoadingState label="Launching your campaign…" className={className} />
  }

  return (
    <div className={cn('flex flex-col items-center gap-3 rounded-xl border border-meera-border bg-meera-surface-2 p-8 text-center', className)}>
      <CheckCircle2 className="h-8 w-8 text-meera-escrow" aria-hidden="true" />
      <div>
        <p className="text-sm font-semibold text-meera-text">Campaign is live</p>
        {typeof toolResult.creatorsInvited === 'number' && (
          <p className="mt-1 text-xs text-meera-text-muted">{toolResult.creatorsInvited} creators invited</p>
        )}
        <p className="mt-1 text-xs text-meera-text-muted">
          Slot and payout stats will appear here as creators respond.
        </p>
      </div>
    </div>
  )
}
