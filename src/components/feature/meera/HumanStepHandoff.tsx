import { Link } from 'react-router-dom'
import { Rocket, Wallet } from 'lucide-react'

import { cn } from '@/lib/utils'

// ---------------------------------------------------------------------------
// Human-step hand-off (P1-7)
//
// Securing funds and going live are the two steps Meera structurally cannot
// take — and must not (see MeeraChatPanel's STAGE_TO_CALL). Without this the
// brand was left at a step nothing could advance, with either silence or a raw
// backend error code and no route anywhere. This gives the step an honest
// state and a real link to the control that does it.
//
// EV-024 (2026-09-20) — lifted out of MeeraChatPanel.tsx into its own module
// so the Living Canvas's funding stage renders the SAME card as the chat
// transcript. It used to live inside the chat panel, which is why the canvas
// grew its own, different answer to the same dead end: a "Fund & go live"
// button wired to a hardcoded placeholder campaign id. One copy, one
// destination, no drift.
//
// Copy rule: "escrow" is banned in brand-facing copy — the vocabulary is
// Secure Payments / secure the funds / secured funds.
// ---------------------------------------------------------------------------

export type HumanStepKey = 'fund' | 'launch'

export const HUMAN_STEP_HANDOFF: Record<
  HumanStepKey,
  { title: string; body: string; to: string; cta: string }
> = {
  fund: {
    title: 'Securing the funds is your step',
    // Mirrors persona.py's own instruction to Meera, so chat and canvas say the
    // same thing: the wallet's fund control only lists ACTIVE campaigns, never
    // drafts — promising it's there before that is the misdirection to avoid.
    body: "Meera can't move money — she's never given that control. Open the campaign from your dashboard and set its budget; once it's active, your wallet has a Secure Campaign Funds card for it.",
    to: '/brand/wallet',
    cta: 'Open your wallet',
  },
  launch: {
    title: 'Going live is your step',
    body: "Meera can't launch a campaign. Launch it from the campaign page once the funds show as secured.",
    to: '/brand/campaigns',
    cta: 'Open your campaigns',
  },
}

export function HumanStepHandoff({ step, className }: { step: HumanStepKey; className?: string }) {
  const copy = HUMAN_STEP_HANDOFF[step]
  const Icon = step === 'fund' ? Wallet : Rocket

  return (
    <div className={cn('rounded-lg border border-meera-border bg-meera-surface-2 p-3', className)}>
      <div className="flex items-start gap-2">
        <Icon className="mt-0.5 h-4 w-4 shrink-0 text-meera-accent" />
        <div className="min-w-0 flex-1">
          <p className="text-xs font-medium text-meera-text">{copy.title}</p>
          <p className="mt-0.5 text-[11px] leading-relaxed text-meera-text-muted">{copy.body}</p>
          <Link
            to={copy.to}
            className="mt-2 inline-block text-[10px] font-medium text-meera-accent underline underline-offset-2"
          >
            {copy.cta} →
          </Link>
        </div>
      </div>
    </div>
  )
}
