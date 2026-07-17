import { MeeraOrb } from '@/components/feature/meera/MeeraOrb'
import { cn } from '@/lib/utils'

export type MeeraPresenceState = 'idle' | 'thinking' | 'talking'

interface MeeraPresenceProps {
  state: MeeraPresenceState
  className?: string
}

/**
 * Corner-docked "living presence" layer for Meera (spec §5A.A / Priya's
 * voice handoff §5). Renders the multi-color MeeraOrb, which drives its own
 * idle / thinking / talking animation from `state` — this component only docks
 * it and forwards the state.
 *
 * Placement contract (caller's responsibility): must be rendered inside a
 * `position: relative` container so the `absolute inset-0` here anchors to it,
 * NEVER `position: fixed`, and the parent must reserve this box so nothing
 * shifts and it never overlaps the Pay CTA / EscrowPill / composer send button.
 * `talking` adds pulse rings that extend past the box — the parent slot must
 * not clip with `overflow-hidden`.
 *
 * State derivation is owned by the caller: talking = TTS isSpeaking OR reply
 * streaming; thinking = phase === 'thinking'; else idle.
 */
export function MeeraPresence({ state, className }: MeeraPresenceProps) {
  return (
    <div
      className={cn('pointer-events-none absolute inset-0 z-20', className)}
      aria-label={`Meera is ${state}`}
    >
      <MeeraOrb state={state} className="h-full w-full" />
    </div>
  )
}
