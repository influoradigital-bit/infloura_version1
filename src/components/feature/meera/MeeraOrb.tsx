import { motion, useReducedMotion } from 'framer-motion'

import { cn } from '@/lib/utils'
import { cssVars } from '@/lib/css-vars'

export type MeeraOrbState = 'idle' | 'thinking' | 'talking'

interface MeeraOrbProps {
  /** Drives the animation energy: idle = calm drift, thinking = swirl, talking = energetic + pulse rings. */
  state: MeeraOrbState
  /** Explicit pixel size. Omit to fill the parent via `h-full w-full` (pass that in className). */
  size?: number
  className?: string
}

/**
 * The "talking with AI" bubble — a living, multi-color orb for Meera.
 *
 * Four blurred color blobs (violet / blue / cyan / pink) drift over a deep
 * base and blend (mix-blend screen) into a lava-lamp glow. The drift speed and
 * amplitude scale with `state`, so the same orb reads as calm when idle, busy
 * when thinking, and vividly alive when Meera is speaking (plus expanding pulse
 * rings on `talking`). Fully self-contained — no global keyframes, no brand
 * dependency; the multi-color look is intentional and NOT themed per-brand.
 *
 * Reduced motion: a static multi-color gradient disc, nothing animated.
 */

// Base drift paths in % of the orb (each blob gets a distinct path so the
// colors never lock together). Amplitude is scaled per state below.
const BLOBS = [
  { color: '#6D5AE6', bgClass: 'bg-[#6D5AE6]', pos: 'top-[2%] left-0',      x: [-8, 14, -8],  y: [-6, 10, -6], scale: [1, 1.15, 1] },
  { color: '#2C7BE5', bgClass: 'bg-[#2C7BE5]', pos: 'top-0 right-0',        x: [12, -10, 12], y: [-10, 8, -10], scale: [1.1, 0.95, 1.1] },
  { color: '#22D3EE', bgClass: 'bg-[#22D3EE]', pos: 'bottom-0 left-[4%]',   x: [6, -12, 6],   y: [12, -8, 12], scale: [0.95, 1.2, 0.95] },
  { color: '#FF5C9E', bgClass: 'bg-[#FF5C9E]', pos: 'bottom-[2%] right-[2%]', x: [-10, 10, -10], y: [8, -12, 8], scale: [1.05, 0.9, 1.05] },
] as const

/** Static fallback disc background — module-level so `cssVars` doesn't recompute it. */
// Gradient layers ONLY — this feeds `background-image: var(--orb-bg)`, where a trailing
// color layer is invalid at computed-value time and collapses the whole property to
// 'none' (F-0210: invisible orb for reduced-motion users). The base color #0B0F1A is a
// separate background-color utility on the element.
const REDUCED_MOTION_BG =
  'radial-gradient(circle at 30% 28%, #6D5AE6 0%, transparent 45%),' +
  'radial-gradient(circle at 72% 30%, #2C7BE5 0%, transparent 45%),' +
  'radial-gradient(circle at 30% 74%, #22D3EE 0%, transparent 45%),' +
  'radial-gradient(circle at 74% 74%, #FF5C9E 0%, transparent 45%)'

/** Static core-disc background for the animated (non-reduced-motion) orb. */
const CORE_BG = 'radial-gradient(circle at 50% 120%, #1B2436 0%, #0B0F1A 70%)'

const CORE_SHADOW: Record<MeeraOrbState, string> = {
  talking: '0 0 22px rgba(109,90,230,0.55), 0 0 8px rgba(34,211,238,0.35)',
  thinking: '0 0 16px rgba(109,90,230,0.4)',
  idle: '0 0 10px rgba(109,90,230,0.25)',
}

// Per-state tuning: [breathe seconds, blob-duration multiplier, amplitude multiplier].
const TUNING: Record<MeeraOrbState, { breathe: number; speed: number; amp: number }> = {
  idle:     { breathe: 5.5, speed: 1,    amp: 1 },
  thinking: { breathe: 2.6, speed: 0.45, amp: 1.15 },
  talking:  { breathe: 1.3, speed: 0.22, amp: 1.35 },
}

const BASE_BLOB_DURATION = [7, 8, 7.5, 8.5] as const

export function MeeraOrb({ state, size, className }: MeeraOrbProps) {
  const reduceMotion = useReducedMotion()
  const { breathe, speed, amp } = TUNING[state]

  // Static fallback — a calm multi-color disc, no motion.
  if (reduceMotion) {
    return (
      <div
        className={cn(
          'relative shrink-0 overflow-hidden rounded-full bg-[#0B0F1A] bg-[image:var(--orb-bg)]',
          className,
          size && 'w-[var(--orb-size)]! h-[var(--orb-size)]!',
        )}
        ref={cssVars({
          '--orb-bg': REDUCED_MOTION_BG,
          ...(size ? { '--orb-size': `${size}px` } : {}),
        })}
        role="img"
        aria-label={`Meera is ${state}`}
      />
    )
  }

  return (
    <div
      className={cn(
        'relative grid shrink-0 place-items-center',
        className,
        size && 'w-[var(--orb-size)]! h-[var(--orb-size)]!',
      )}
      ref={size ? cssVars({ '--orb-size': `${size}px` }) : undefined}
      role="img"
      aria-label={`Meera is ${state}`}
    >
      {/* Expanding pulse rings — talking only. */}
      {state === 'talking' &&
        [0, 0.8].map((delay, i) => (
          <motion.span
            key={i}
            className={cn(
              'pointer-events-none absolute inset-0 rounded-full border-2',
              i === 0 ? 'border-[#6D5AE6]' : 'border-[#22D3EE]',
            )}
            initial={{ scale: 1, opacity: 0.5 }}
            animate={{ scale: 1.9, opacity: 0 }}
            transition={{ duration: 1.6, repeat: Infinity, ease: 'easeOut', delay }}
            aria-hidden="true"
          />
        ))}

      {/* The orb itself — breathes as a whole, blobs drift inside. */}
      <motion.div
        className="relative h-full w-full overflow-hidden rounded-full bg-[image:var(--orb-core-bg)] shadow-[var(--orb-core-shadow)]"
        ref={cssVars({ '--orb-core-bg': CORE_BG, '--orb-core-shadow': CORE_SHADOW[state] })}
        animate={{ scale: [1, 1.04, 1] }}
        transition={{ duration: breathe, repeat: Infinity, ease: 'easeInOut' }}
      >
        {BLOBS.map((blob, i) => (
          <motion.span
            key={blob.color}
            className={cn(
              'absolute h-[78%] w-[78%] rounded-full blur-[14px] mix-blend-screen opacity-95',
              blob.bgClass,
              blob.pos,
            )}
            animate={{
              x: blob.x.map((v) => `${v * amp}%`),
              y: blob.y.map((v) => `${v * amp}%`),
              scale: [...blob.scale],
            }}
            transition={{
              duration: BASE_BLOB_DURATION[i] * speed,
              repeat: Infinity,
              ease: 'easeInOut',
            }}
            aria-hidden="true"
          />
        ))}

        {/* Glass sheen highlight. */}
        <span
          className="pointer-events-none absolute inset-0 rounded-full bg-[radial-gradient(circle_at_32%_28%,rgba(255,255,255,0.5),rgba(255,255,255,0)_42%)]"
          aria-hidden="true"
        />
      </motion.div>
    </div>
  )
}
