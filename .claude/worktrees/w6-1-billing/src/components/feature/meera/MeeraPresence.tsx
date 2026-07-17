import { motion, useReducedMotion } from 'framer-motion'

import { BrandAvatar } from '@/components/ui/brand-avatar'
import { VoiceWaveform } from '@/components/ui/voice-waveform'
import {
  MEERA_PRESENCE_IDLE_DURATION,
  MEERA_PRESENCE_THINKING_DURATION,
} from '@/data/motion-tokens'
import { cn } from '@/lib/utils'

export type MeeraPresenceState = 'idle' | 'thinking' | 'talking'

interface MeeraPresenceProps {
  state: MeeraPresenceState
  className?: string
}

/**
 * Corner-docked "living presence" layer for Meera (spec §5A.A / Priya's
 * voice handoff §5). This is a state layer ON TOP of BrandAvatar — not a
 * second avatar. Subtle and small on purpose: a tasteful pulsing presence
 * reads premium, an animated face reads toy/uncanny.
 *
 * Placement contract (caller's responsibility, enforced by usage, not by
 * this component): must be rendered inside a `position: relative` container
 * with `position: absolute` here — NEVER `position: fixed` — and the parent
 * must reserve this component's box so nothing shifts and it never overlaps
 * the Pay CTA / EscrowPill / composer send button.
 *
 * State derivation (owned by the caller): talking = TTS isSpeaking OR reply
 * streaming; thinking = phase === 'thinking'; else idle. This component only
 * renders whatever state it's given — no local state machine here.
 *
 * Color: ring uses --meera-accent ONLY. Reduced motion -> static avatar +
 * online dot, nothing animated (acceptance #4).
 */
export function MeeraPresence({ state, className }: MeeraPresenceProps) {
  const reduceMotion = useReducedMotion()

  if (reduceMotion) {
    return (
      <div
        className={cn('pointer-events-none absolute z-20', className)}
        aria-label={`Meera is ${state}`}
      >
        <BrandAvatar initials="M" size="sm" online />
      </div>
    )
  }

  return (
    <div
      className={cn('pointer-events-none absolute z-20 flex items-center gap-1.5', className)}
      aria-label={`Meera is ${state}`}
    >
      <div className="relative">
        {/* Idle: gentle breathing pulse ring. Thinking: faster shimmer ring. */}
        {state !== 'talking' && (
          <motion.span
            className="absolute inset-0 rounded-full"
            style={{ boxShadow: '0 0 0 2px var(--meera-accent)' }}
            animate={
              state === 'thinking'
                ? { opacity: [0.25, 0.8, 0.25], scale: [1, 1.18, 1] }
                : { opacity: [0.2, 0.55, 0.2], scale: [1, 1.08, 1] }
            }
            transition={{
              duration: state === 'thinking' ? MEERA_PRESENCE_THINKING_DURATION : MEERA_PRESENCE_IDLE_DURATION,
              repeat: Infinity,
              ease: 'easeInOut',
            }}
            aria-hidden="true"
          />
        )}
        <BrandAvatar initials="M" size="sm" online={state !== 'talking'} />
      </div>
      {state === 'talking' && (
        <span className="rounded-full bg-meera-surface/90 px-1.5 py-1 shadow-sm">
          <VoiceWaveform active size="sm" />
        </span>
      )}
    </div>
  )
}
