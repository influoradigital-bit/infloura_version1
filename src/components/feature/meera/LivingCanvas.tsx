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
import type { MeeraStagePayloads } from '@/hooks/useMeeraStage'
import { computeFee } from '@/data/meera-mock'
import { MOCK_CAMPAIGN_PLAN } from '@/data/meera-mock'
import { isApiLive } from '@/lib/api'
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
  /** Latest live tool_result payload per stage (empty in mock mode) — each Stage component narrows its own shape. */
  stagePayloads?: MeeraStagePayloads
  className?: string
}

function escrowStateForStage(stage: MeeraStageId, isPaid: boolean): EscrowPillState {
  if (stage === 'funding') return isPaid ? 'secured' : 'securing'
  if (stage === 'live') return 'releasing'
  return 'unfunded'
}

/** Best-effort total for the header EscrowPill in live mode — prefers the
 * server-confirmed `request_payment.serverAmount`, falls back to the earlier
 * `calculate_budget.suggestedPoolTotal` (Spring DTOs — see lib/meera-api.ts).
 * `undefined` (never a mock number) if neither has arrived yet; EscrowPill
 * renders its label without an amount in that case. */
function liveTotalLabel(stagePayloads: MeeraStagePayloads | undefined): string | undefined {
  const funding = stagePayloads?.funding
  if (funding && typeof funding === 'object' && typeof (funding as { serverAmount?: unknown }).serverAmount === 'number') {
    return formatINR((funding as { serverAmount: number }).serverAmount)
  }
  const recommend = stagePayloads?.recommend
  if (
    recommend &&
    typeof recommend === 'object' &&
    typeof (recommend as { suggestedPoolTotal?: unknown }).suggestedPoolTotal === 'number'
  ) {
    return formatINR((recommend as { suggestedPoolTotal: number }).suggestedPoolTotal)
  }
  return undefined
}

/** Right panel: header stays mounted (title + T1 pill + presence), body morphs through 5 stages. */
export function LivingCanvas({ stage, isPaid, onPay, onGoLive, presenceState, stagePayloads, className }: LivingCanvasProps) {
  const config = STAGE_CONFIG[stage]
  const live = isApiLive()
  const totalLabel = live
    ? liveTotalLabel(stagePayloads)
    : formatINR(computeFee(MOCK_CAMPAIGN_PLAN.pool, MOCK_CAMPAIGN_PLAN.feePercent).total)
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
          {stage === 'snapshot' && <StageSnapshot toolResult={stagePayloads?.snapshot} />}
          {stage === 'recommend' && <StageRecommend toolResult={stagePayloads?.recommend} />}
          {stage === 'matching' && <StageMatching toolResult={stagePayloads?.matching} />}
          {stage === 'funding' && (
            <StageFunding
              paid={isPaid}
              onPay={onPay}
              onGoLive={onGoLive}
              paymentToolResult={stagePayloads?.funding}
              budgetToolResult={stagePayloads?.recommend}
            />
          )}
          {stage === 'live' && <StageLive toolResult={stagePayloads?.live} />}
        </StageMorph>
      </div>
    </div>
  )
}
