import { TriangleAlert } from 'lucide-react'

import { cn } from '@/lib/utils'

export interface EstimateBadgeProps {
  /**
   * 2-state provenance tag off `GetCampaignPerformanceResult.provenance`
   * (`lib/meera-api.ts`) — collapsed from an earlier 3-state draft per
   * Priya/Ash's design-review ruling (`wiki/build/phase2-priya-review.md`
   * §2 Q1, `wiki/build/phase2-ash-review.md` Q1): an outcome number is
   * either measured or claimed, never "formula-inferred," so `INFERRED`
   * doesn't apply here. `undefined` or `'PLATFORM_VERIFIED'` renders
   * nothing — quiet by default, same philosophy as `ThemeProvenanceBadge`.
   *
   * Dormant in v1: the backend only ever emits `PLATFORM_VERIFIED` today
   * (self-reported numbers are omitted, not surfaced-with-flag), so this
   * badge has no live trigger yet. It activates automatically the moment a
   * `SELF_REPORTED` result is ever returned — no component change needed
   * (Priya F4: "build it quiet, don't over-invest").
   */
  provenance?: 'PLATFORM_VERIFIED' | 'SELF_REPORTED'
  className?: string
}

/**
 * Meera performance-card provenance chip (StagePerformance, 2.4).
 *
 * a11y: text + icon, never color-only (`TriangleAlert` + literal
 * "Estimated" label, matching `ThemeProvenanceBadge`'s Sparkles + "Spotted
 * by AI" pattern). `role="note"` — a static annotation, not a live region.
 * `sr-only` sentence carries the full caveat for screen readers.
 *
 * NEVER spoken aloud: this text never enters `useVoiceOutput.speak()`'s
 * `assistantText` buffer (`MeeraChatPanel.tsx`) — badges are DOM-only, by
 * construction, not by a runtime guard. If a future "read the canvas
 * aloud" feature is ever added, it must explicitly exclude badge content.
 */
export function EstimateBadge({ provenance, className }: EstimateBadgeProps) {
  if (!provenance || provenance === 'PLATFORM_VERIFIED') return null

  return (
    <span
      role="note"
      className={cn(
        'inline-flex items-center gap-1 rounded-full border border-meera-border',
        'bg-meera-surface-2 px-2 py-0.5 text-[11px] font-medium text-meera-text-muted',
        className,
      )}
    >
      <TriangleAlert className="h-3 w-3 text-meera-warning" aria-hidden="true" />
      <span aria-hidden="true">Estimated</span>
      <span className="sr-only">
        This number is self-reported, not platform-verified — treat it as directional.
      </span>
    </span>
  )
}

export default EstimateBadge
