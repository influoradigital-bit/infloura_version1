import { useState } from 'react'
import { ArrowUp } from 'lucide-react'

import { QuickReplyChip } from '@/components/ui/quick-reply-chip'
import { MicButton } from '@/components/ui/mic-button'
import { useVoiceInput, MAX_TRANSCRIPT_LENGTH } from '@/hooks/useVoiceInput'
import { MEERA_COMPOSER, MEERA_VOICE_COPY } from '@/data/meera-copy'
import { cn } from '@/lib/utils'

interface ComposerProps {
  onSend: (text: string) => void
  /** Contextual chips for the CURRENT turn — sourced from `MeeraTurn.suggestedReplies`, never static. */
  suggestedReplies?: string[]
  /** Paywall-style lock (credit-gated). Shows `disabledHint` + paused placeholder. */
  disabled?: boolean
  /**
   * Momentary send lock while Meera is revealing lines or thinking between
   * turns — the input still looks live, it just can't submit mid-beat. No
   * paywall copy shown; this is a turn-engine pacing state, not a gate.
   */
  sendLocked?: boolean
  /**
   * One-time starter text to pre-fill the input with (e.g. the "Ask Meera"
   * help pre-seed). Seeds the draft only; it is never auto-sent — the user
   * reviews/edits and sends themselves.
   */
  initialDraft?: string
  className?: string
}

/**
 * Input + contextual quick-reply chips + send. Disabled variant shows the
 * paused placeholder. Voice input (spec §5A.C) is layered on top: the mic
 * only ever writes the cleaned transcript into `value`, editable — it never
 * calls `onSend`. If STT is unsupported, the mic simply isn't rendered and
 * the text path is unaffected.
 */
export function Composer({ onSend, suggestedReplies = [], disabled, sendLocked, initialDraft, className }: ComposerProps) {
  const [value, setValue] = useState(() => initialDraft ?? '')
  const [voiceFallback, setVoiceFallback] = useState<string | null>(null)
  /** Announced via aria-live so screen-reader users hear what Meera transcribed. */
  const [transcriptionAnnouncement, setTranscriptionAnnouncement] = useState<string | null>(null)
  const locked = disabled || sendLocked

  const { supported: voiceInputSupported, phase, start, stop } = useVoiceInput({
    onResult: (cleanedText) => {
      // Edit-first, always. Land the cleaned transcript in the composer's
      // existing value for the user to review and send themselves.
      setVoiceFallback(null)
      setTranscriptionAnnouncement(cleanedText)
      setValue((prev) => (prev ? `${prev} ${cleanedText}` : cleanedText))
    },
    onError: () => {
      setTranscriptionAnnouncement(null)
      setVoiceFallback(MEERA_VOICE_COPY.sttFallback)
    },
  })

  const handleSend = () => {
    const trimmed = value.trim()
    if (!trimmed || locked) return
    onSend(trimmed)
    setValue('')
    setVoiceFallback(null)
    setTranscriptionAnnouncement(null)
  }

  return (
    <div className={cn('space-y-2.5', className)}>
      {suggestedReplies.length > 0 && (
        <div className="flex gap-2 overflow-x-auto pb-1 scrollbar-thin" role="group" aria-label="Quick replies">
          {suggestedReplies.map((label) => (
            <QuickReplyChip key={label} label={label} onClick={() => !locked && onSend(label)} />
          ))}
        </div>
      )}
      <div className="flex items-end gap-2 rounded-xl border border-meera-border bg-meera-surface p-2">
        <textarea
          value={value}
          onChange={(e) => setValue(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
              e.preventDefault()
              handleSend()
            }
          }}
          disabled={locked}
          rows={1}
          maxLength={MAX_TRANSCRIPT_LENGTH}
          placeholder={disabled ? MEERA_COMPOSER.placeholderPaused : MEERA_COMPOSER.placeholder}
          className="max-h-32 min-h-9 flex-1 resize-none bg-transparent px-2 py-1.5 text-sm text-meera-text placeholder:text-meera-text-muted focus:outline-none disabled:cursor-not-allowed"
        />
        {voiceInputSupported && !disabled && (
          <MicButton phase={phase} onStart={start} onStop={stop} />
        )}
        <button
          type="button"
          onClick={handleSend}
          disabled={locked || !value.trim()}
          aria-label="Send message"
          className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-meera-accent text-white transition-[background-color,opacity] duration-150 ease-out active:scale-[0.97] disabled:cursor-not-allowed disabled:opacity-40"
        >
          <ArrowUp className="h-4 w-4" />
        </button>
      </div>
      {/* Transcription result region: announces the cleaned text (or the STT
          fallback copy) so a screen-reader user always hears what happened —
          never a silent dead end (Priya's voice handoff §8). */}
      <div aria-live="polite" className="sr-only">
        {transcriptionAnnouncement ?? voiceFallback}
      </div>
      {voiceFallback && <p className="px-1 text-xs text-meera-text-muted">{voiceFallback}</p>}
      {disabled && <p className="px-1 text-xs text-meera-text-muted">{MEERA_COMPOSER.disabledHint}</p>}
    </div>
  )
}
