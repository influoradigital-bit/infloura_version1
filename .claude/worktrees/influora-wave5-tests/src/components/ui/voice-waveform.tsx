import { motion, useReducedMotion } from 'framer-motion'

import { MEERA_WAVEFORM_BAR_COUNT, MEERA_WAVEFORM_LOOP_DURATION } from '@/data/motion-tokens'
import { cn } from '@/lib/utils'

interface VoiceWaveformProps {
  /** Drives the animation — true while speaking (TTS) or listening (STT). */
  active: boolean
  size?: 'sm' | 'md'
  className?: string
}

const SIZE_HEIGHT: Record<NonNullable<VoiceWaveformProps['size']>, number> = {
  sm: 12,
  md: 16,
}

/**
 * Shared audio-style waveform for MeeraPresence-talking and MicButton-listening
 * (Priya's voice handoff §5). Time-based, NOT audio-reactive/FFT — running an
 * FFT on speechSynthesis output is impossible (no audio stream to analyze),
 * so this is an honest looping bar animation that reads as "she's speaking"
 * without pretending to be real audio analysis.
 *
 * Color: --meera-accent ONLY. Escrow-green must never appear here.
 */
export function VoiceWaveform({ active, size = 'md', className }: VoiceWaveformProps) {
  const reduceMotion = useReducedMotion()
  const height = SIZE_HEIGHT[size]

  // Reduced motion (or fully inactive): static bars, no loop at all.
  if (reduceMotion) {
    return (
      <span
        className={cn('inline-flex items-center gap-0.5', className)}
        style={{ height }}
        role="img"
        aria-label={active ? 'Meera is speaking' : 'Voice waveform, idle'}
      >
        {Array.from({ length: MEERA_WAVEFORM_BAR_COUNT }).map((_, i) => (
          <span
            key={i}
            className="w-[3px] rounded-full bg-meera-accent"
            style={{ height: active ? height * 0.5 : height * 0.2 }}
          />
        ))}
      </span>
    )
  }

  return (
    <span
      className={cn('inline-flex items-center gap-0.5', className)}
      style={{ height }}
      role="img"
      aria-label={active ? 'Meera is speaking' : 'Voice waveform, idle'}
    >
      {Array.from({ length: MEERA_WAVEFORM_BAR_COUNT }).map((_, i) => (
        <motion.span
          key={i}
          className="w-[3px] rounded-full bg-meera-accent"
          animate={
            active
              ? { height: [height * 0.25, height, height * 0.35, height * 0.8, height * 0.25] }
              : { height: height * 0.2 }
          }
          transition={
            active
              ? {
                  duration: MEERA_WAVEFORM_LOOP_DURATION,
                  repeat: Infinity,
                  ease: 'easeInOut',
                  delay: i * 0.08,
                }
              : { duration: 0.2 }
          }
        />
      ))}
    </span>
  )
}
