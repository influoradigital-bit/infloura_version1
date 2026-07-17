import { Volume2, VolumeX } from 'lucide-react'

import { MEERA_VOICE_COPY } from '@/data/meera-copy'
import { cn } from '@/lib/utils'

interface VoiceToggleProps {
  enabled: boolean
  onToggle: (next: boolean) => void
  className?: string
}

/**
 * Persistent speak-replies on/off control (spec §5A.B). Only rendered by the
 * caller when `useVoiceOutput().supported` is true — an unsupported browser
 * should never see a toggle that does nothing.
 */
export function VoiceToggle({ enabled, onToggle, className }: VoiceToggleProps) {
  return (
    <button
      type="button"
      onClick={() => onToggle(!enabled)}
      aria-pressed={enabled}
      aria-label={enabled ? MEERA_VOICE_COPY.voiceToggleOnLabel : MEERA_VOICE_COPY.voiceToggleOffLabel}
      title={enabled ? MEERA_VOICE_COPY.voiceToggleOnLabel : MEERA_VOICE_COPY.voiceToggleOffLabel}
      className={cn(
        'inline-flex h-8 w-8 shrink-0 items-center justify-center rounded-full border transition-colors duration-150 ease-out focus-visible:outline-none focus-visible:ring-4 focus-visible:ring-[var(--meera-accent-glow)]',
        enabled
          ? 'border-transparent bg-meera-accent-soft text-meera-accent'
          : 'border-meera-border bg-meera-surface text-meera-text-muted hover:text-meera-text',
        className,
      )}
    >
      {enabled ? <Volume2 className="h-4 w-4" aria-hidden="true" /> : <VolumeX className="h-4 w-4" aria-hidden="true" />}
    </button>
  )
}
