import { Loader2, Mic } from 'lucide-react'

import { VoiceWaveform } from '@/components/ui/voice-waveform'
import type { VoiceInputPhase } from '@/hooks/useVoiceInput'
import { MEERA_VOICE_COPY } from '@/data/meera-copy'
import { cn } from '@/lib/utils'

interface MicButtonProps {
  phase: VoiceInputPhase
  onStart: () => void
  onStop: () => void
  className?: string
}

/**
 * Composer mic control (spec §5A.C). Only rendered by the caller when
 * `useVoiceInput().supported` is true — STT is effectively Chromium-only,
 * so Safari/Firefox never see a mic button that can't work.
 *
 * States: idle -> listening (waveform, tap to stop) -> transcribing
 * (spinner while cleanup runs) -> back to idle once the composer receives
 * the editable cleaned text.
 */
export function MicButton({ phase, onStart, onStop, className }: MicButtonProps) {
  const listening = phase === 'listening'
  const transcribing = phase === 'transcribing'

  const label = listening
    ? MEERA_VOICE_COPY.micListeningLabel
    : transcribing
      ? MEERA_VOICE_COPY.micTranscribingLabel
      : MEERA_VOICE_COPY.micIdleLabel

  return (
    <button
      type="button"
      onClick={listening ? onStop : onStart}
      disabled={transcribing}
      aria-label={label}
      aria-pressed={listening}
      title={label}
      className={cn(
        'flex h-9 w-9 shrink-0 items-center justify-center rounded-lg border transition-colors duration-150 ease-out focus-visible:outline-none focus-visible:ring-4 focus-visible:ring-[var(--meera-accent-glow)] disabled:cursor-not-allowed disabled:opacity-60',
        listening
          ? 'border-transparent bg-meera-accent-soft text-meera-accent'
          : 'border-meera-border bg-meera-surface text-meera-text-muted hover:text-meera-text',
        className,
      )}
    >
      {transcribing ? (
        <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />
      ) : listening ? (
        <VoiceWaveform active size="sm" />
      ) : (
        <Mic className="h-4 w-4" aria-hidden="true" />
      )}
    </button>
  )
}
