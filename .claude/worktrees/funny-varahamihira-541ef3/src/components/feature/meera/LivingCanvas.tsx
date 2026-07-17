import { StageMorph } from '@/components/motion/StageMorph'
import { EscrowPill, type EscrowPillState } from '@/components/ui/escrow-pill'
import { MeeraPresence, type MeeraPresenceState } from '@/components/feature/meera/MeeraPresence'
import { StageSnapshot } from '@/components/feature/meera/StageSnapshot'
import { StageRecommend } from '@/components/feature/meera/StageRecommend'
import { StageMatching } from '@/components/feature/meera/StageMatching'
import { StageFunding } from '@/components/feature/meera/StageFunding'
import { StageLive } from '@/components/feature/meera/StageLive'
import { STAGE_CONFIG } from '@/data/stage-config'
import type { MeeraStageId } from '@/data/stage-config'
import { computeFee } from '@/data/meera-mock'
import { MOCK_CAMPAIGN_PLAN } from '@/data/meera-mock'
import { formatINR } from '@/lib/utils'
import { cn } from '@/lib/utils'

interface LivingCanvasProps {
  stage: MeeraStageId
  isPaid: boolean
  onPay: () => Promise<void> | void
  /** Fired only after the escrow-lock hero completes (B1 fix) — advances funding -> live. */
  onGoLive: () => void
  /** Living-presence state (spec §5A.A), derived by the caller from MeeraChatPanel's phase + TTS isSpeaking. */
  presenceState: MeeraPresenceState
  className?: string
}

function escrowStateForStage(stage: MeeraStageId, isPaid: boolean): EscrowPillState {
  if (stage === 'funding') return isPaid ? 'secured' : 'securing'
  if (stage === 'live') return 'releasing'
  return 'unfunded'
}

/** Right panel: header stays mounted (title + T1 pill + presence), body morphs through 5 stages. */
export function LivingCanvas({ stage, isPaid, onPay, onGoLive, presenceState, className }: LivingCanvasProps) {
  const config = STAGE_CONFIG[stage]
  const { total } = computeFee(MOCK_CAMPAIGN_PLAN.pool, MOCK_CAMPAIGN_PLAN.feePercent)
  const totalLabel = formatINR(total)
  const escrowState = escrowStateForStage(stage, isPaid)

  return (
    <div className={cn('flex h-full flex-col bg-meera-bg', className)}>
      {/* Mounted header — MeeraPresence docks here, corner-anchored, so it
          never overlaps the scrolling stage body, the Pay CTA, or the
          EscrowPill. It sits in its own reserved slot to the left of the
          title (position: relative wrapper, presence: position: absolute
          inside it) so there is no layout shift and no fixed-to-viewport
          risk (Priya's voice handoff §5). */}
      <div className="flex shrink-0 items-center justify-between gap-3 border-b border-meera-border bg-meera-surface px-4 py-3 sm:px-6">
        <div className="flex min-w-0 items-center gap-3">
          <div className="relative h-8 w-8 shrink-0">
            <MeeraPresence state={presenceState} className="left-0 top-0" />
          </div>
          <div className="min-w-0">
            <h2 className="truncate text-base font-semibold text-meera-text sm:text-lg">{config.title}</h2>
            <p className="truncate text-xs text-meera-text-muted">{config.subtitle}</p>
          </div>
        </div>
        <EscrowPill
          state={escrowState}
          amount={escrowState === 'secured' || escrowState === 'releasing' ? totalLabel : undefined}
        />
      </div>

      {/* Morphing body */}
      <div className="flex-1 overflow-y-auto p-4 scrollbar-thin sm:p-6">
        <StageMorph stageKey={stage}>
          {stage === 'snapshot' && <StageSnapshot />}
          {stage === 'recommend' && <StageRecommend />}
          {stage === 'matching' && <StageMatching />}
          {stage === 'funding' && <StageFunding paid={isPaid} onPay={onPay} onGoLive={onGoLive} />}
          {stage === 'live' && <StageLive />}
        </StageMorph>
      </div>
    </div>
  )
}
