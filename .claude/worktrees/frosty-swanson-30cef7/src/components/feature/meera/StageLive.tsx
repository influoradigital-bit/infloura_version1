import { StatPair } from '@/components/ui/stat-pair'
import { SlotProgressBar } from '@/components/ui/slot-progress-bar'
import { CreatorCard } from '@/components/ui/creator-card'
import { PayoutLedger } from '@/components/feature/meera/PayoutLedger'
import { MEERA_STAT_LABELS } from '@/data/meera-copy'
import { MOCK_CREATORS, MOCK_LIVE_STATS } from '@/data/meera-mock'
import { cn } from '@/lib/utils'

interface StageLiveProps {
  className?: string
}

/** Stage 5 — dashboard: invites count-up, slot tracker, accepted list, payout ledger. */
export function StageLive({ className }: StageLiveProps) {
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
