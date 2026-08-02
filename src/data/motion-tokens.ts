/**
 * Meera-specific motion constants (spec §5 / handoff §7).
 * Never inline these — import from here so every Meera surface stays in sync.
 * General-purpose constants (EASE_OUT, DURATION_NORMAL, etc.) still come from
 * `@/lib/motion-config` — these are the Meera-only additions layered on top.
 */

/** Standard spring used across Meera cards, bubbles, and stage bodies. */
export const MEERA_SPRING = {
  type: 'spring' as const,
  stiffness: 60,
  damping: 18,
}

/** Entry ease for Meera cinematic moments (matches /3d-cinematic-web preset). */
export const MEERA_EASE_ENTRY = [0.23, 1, 0.32, 1] as const

/** Stagger delay formula: 0.15 + index * 0.10, capped at 8 items (spec §5). */
export const MEERA_STAGGER_BASE = 0.15
export const MEERA_STAGGER_STEP = 0.1
export const MEERA_STAGGER_MAX_ITEMS = 8

export function meeraStaggerDelay(index: number): number {
  return MEERA_STAGGER_BASE + Math.min(index, MEERA_STAGGER_MAX_ITEMS - 1) * MEERA_STAGGER_STEP
}

/** Shared entry variant for staggered cards/creator tiles. */
export const MEERA_STAGGER_ITEM_VARIANTS = {
  hidden: { opacity: 0, y: 12, scale: 0.9 },
  visible: {
    opacity: 1,
    y: 0,
    scale: 1,
    transition: MEERA_SPRING,
  },
}

/** Message bubble entry (~260ms spring). */
export const MEERA_BUBBLE_TRANSITION = {
  type: 'spring' as const,
  stiffness: 60,
  damping: 18,
  duration: 0.26,
}

/** Stage-morph body transitions — header stays mounted, only body swaps. */
export const MEERA_STAGE_EXIT = { opacity: 0, y: -8 }
export const MEERA_STAGE_ENTER_FROM = { opacity: 0, y: 12 }
export const MEERA_STAGE_ENTER_TO = { opacity: 1, y: 0 }
export const MEERA_STAGE_EXIT_DURATION = 0.18

/** Escrow-lock hero timeline (T2) — total ~1.6s. */
export const MEERA_LOCK_TIMELINE = {
  meterFillMs: 700,
  shackleDropMs: 420,
  auraPulseMs: 480,
  captionDelayMs: 1120,
  totalMs: 1600,
}

/** Reduced-motion aura fade fallback. */
export const MEERA_REDUCED_AURA_MS = 150

/**
 * Voice & Living Presence (spec §5A / Priya's voice handoff §5, §9).
 * VoiceWaveform is time-based (loops while active), never audio-reactive —
 * there is no audio stream to analyze from speechSynthesis output.
 */
export const MEERA_WAVEFORM_BAR_COUNT = 5
export const MEERA_WAVEFORM_LOOP_DURATION = 0.9 // seconds per bar cycle
