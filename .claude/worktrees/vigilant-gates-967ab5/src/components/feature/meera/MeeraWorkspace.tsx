import { useRef, useState } from 'react'
import { ChevronUp } from 'lucide-react'

import { Sheet, SheetContent, SheetHeader, SheetTitle } from '@/components/ui/sheet'
import { MeeraChatPanel, type Phase } from '@/components/feature/meera/MeeraChatPanel'
import { LivingCanvas } from '@/components/feature/meera/LivingCanvas'
import type { MeeraPresenceState } from '@/components/feature/meera/MeeraPresence'
import { useBrandTheme } from '@/hooks/useBrandTheme'
import { useMeeraStage } from '@/hooks/useMeeraStage'
import { MEERA_MOBILE } from '@/data/meera-copy'
import { MOCK_BRAND_SNAPSHOT } from '@/data/meera-mock'
import api from '@/lib/api'
import { cn } from '@/lib/utils'

/**
 * Placeholder deal id for the demo escrow call. In live mode this must be the real deal id threaded
 * from the deal/collaboration context, not a constant.
 */
const MEERA_DEMO_DEAL_ID = 'meera_demo_deal'

/**
 * Derives MeeraPresence's state from the chat panel's existing phase +
 * TTS isSpeaking — no new state machine, per Priya's voice handoff §5:
 * talking (isSpeaking) > thinking (phase === 'thinking') > idle.
 */
function derivePresenceState(phase: Phase, isSpeaking: boolean): MeeraPresenceState {
  if (isSpeaking) return 'talking'
  if (phase === 'thinking') return 'thinking'
  return 'idle'
}

/**
 * 50/50 workspace shell for /brand/meera. Fixed split with a 1px
 * --meera-border-strong seam on desktop; collapses to full-screen chat +
 * bottom-tab-triggered sheet below 768px (spec §4). Height follows the
 * BrandLayout convention: h-[calc(100vh-3.5rem)] (handoff §1), never 100vh.
 */
export function MeeraWorkspace() {
  const rootRef = useRef<HTMLDivElement>(null)
  useBrandTheme(rootRef, MOCK_BRAND_SNAPSHOT.brandColorHex)

  const { stage, isPaid, advance, markPaid } = useMeeraStage('snapshot')
  const [sheetOpen, setSheetOpen] = useState(false)

  // Living-presence state (spec §5A.A) — owned here as the single point
  // combining MeeraChatPanel's phase with TTS isSpeaking, threaded via
  // props one level down to LivingCanvas. A tiny amount of prop bubbling,
  // not worth a context for two values at one hop.
  const [chatPhase, setChatPhase] = useState<Phase>('revealing')
  const [isSpeaking, setIsSpeaking] = useState(false)
  const presenceState = derivePresenceState(chatPhase, isSpeaking)

  /**
   * B1 (Kabir security audit) — escrow/"Secured" state is SERVER truth, never a client flag.
   *
   * The old handler faked it with `setTimeout(900) + markPaid()`, which meant a user could reach a
   * "funds secured" UI from devtools with no real payment. Funding now goes through the
   * server-authoritative endpoint (`payments.fundEscrow` — no client-supplied amount, idempotent per
   * api.ts) and the client only marks paid when the SERVER reports the escrow FUNDED.
   *
   * LIVE-MODE CONTRACT (must hold before VITE_API_MODE=live): `isPaid`/"Secured" must ultimately be
   * driven by the server-confirmed escrow / `payment.released` SSE event (see the /stream contract in
   * api.ts). This POST resolving is necessary but NOT sufficient — the UI must reconcile against the
   * server event and must never set paid state from client code alone. Advancing to `live` stays a
   * SEPARATE explicit step (see StageFunding's "Approve & release" CTA), never bundled in here.
   */
  const handlePay = async () => {
    const res = await api.payments.fundEscrow(MEERA_DEMO_DEAL_ID)
    if (res.status === 'FUNDED') {
      markPaid()
    }
  }

  const handleGoLive = () => {
    advance('confirm_launch')
  }

  return (
    <div
      ref={rootRef}
      className="flex h-[calc(100vh-3.5rem)] w-full overflow-hidden bg-meera-bg"
    >
      {/* Left — Meera chat. Full width on mobile, 50% desktop, 45% tablet-with-canvas. */}
      <div className="flex h-full w-full flex-col md:w-1/2 lg:w-1/2">
        <MeeraChatPanel
          onFunctionCall={advance}
          onPhaseChange={setChatPhase}
          onSpeakingChange={setIsSpeaking}
          className="h-full"
        />
      </div>

      {/* Seam */}
      <div className="hidden w-px shrink-0 bg-meera-border-strong md:block" aria-hidden="true" />

      {/* Right — Living Canvas. Hidden on mobile (moves into the sheet below). */}
      <div className="hidden h-full md:flex md:w-1/2 lg:w-1/2">
        <LivingCanvas
          stage={stage}
          isPaid={isPaid}
          onPay={handlePay}
          onGoLive={handleGoLive}
          presenceState={presenceState}
          className="h-full w-full"
        />
      </div>

      {/* Mobile: persistent bottom tab to open the canvas sheet */}
      <button
        type="button"
        onClick={() => setSheetOpen(true)}
        className={cn(
          'fixed inset-x-4 bottom-4 z-30 flex items-center justify-between rounded-full border border-meera-border-strong bg-meera-surface px-5 py-3 text-sm font-semibold text-meera-text shadow-lg md:hidden',
        )}
      >
        <span>{MEERA_MOBILE.viewCampaignTab}</span>
        <ChevronUp className="h-4 w-4 text-meera-text-muted" />
      </button>

      {/* Mobile: canvas as a ~90vh sheet */}
      <Sheet open={sheetOpen} onOpenChange={setSheetOpen}>
        <SheetContent side="bottom" className="h-[90vh] p-0 md:hidden">
          <SheetHeader className="border-b border-meera-border px-4 py-3">
            <SheetTitle className="text-sm">{MEERA_MOBILE.sheetTitle}</SheetTitle>
          </SheetHeader>
          <LivingCanvas
            stage={stage}
            isPaid={isPaid}
            onPay={handlePay}
            onGoLive={handleGoLive}
            presenceState={presenceState}
            className="h-[calc(90vh-3.25rem)]"
          />
        </SheetContent>
      </Sheet>
    </div>
  )
}
