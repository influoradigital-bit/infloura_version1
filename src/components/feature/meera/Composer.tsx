import { useRef, useState } from 'react'
import { ArrowUp } from 'lucide-react'

import { QuickReplyChip } from '@/components/ui/quick-reply-chip'
import { MicButton } from '@/components/ui/mic-button'
import { useVoiceInput, MAX_TRANSCRIPT_LENGTH } from '@/hooks/useVoiceInput'
import { MEERA_COMPOSER, MEERA_VOICE_COPY } from '@/data/meera-copy'
import { cn } from '@/lib/utils'

interface ComposerProps {
  /**
   * `lang` (W3): the Sarvam-detected language of the utterance that produced
   * this text via voice input (`lang_detected` from
   * `/meera/voice/transcribe`), so the caller can speak the reply back in
   * the same language. Undefined for typed input or a browser-STT fallback
   * transcript (no detection available).
   */
  onSend: (text: string, lang?: string) => void
  /** Contextual chips for the CURRENT turn — sourced from `MeeraTurn.suggestedReplies`, never static. */
  suggestedReplies?: string[]
  /**
   * R6 — starter templates (shown when the chat is empty). Tapping one PRE-FILLS
   * the composer with the template's text for the user to edit the [blanks] and
   * send themselves — it never sends on tap, unlike `suggestedReplies`.
   */
  templates?: string[]
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
export function Composer({ onSend, suggestedReplies = [], templates = [], disabled, sendLocked, initialDraft, className }: ComposerProps) {
  const [value, setValue] = useState(() => initialDraft ?? '')
  const [voiceFallback, setVoiceFallback] = useState<string | null>(null)
  /**
   * W3: language Sarvam detected for the most recent voice-inserted transcript
   * (`lang_detected`, e.g. `hi-IN`), so `onSend` can carry it through to the
   * spoken reply. Cleared on manual edit — once the user has hand-typed over
   * or after the voice text, the detection no longer reliably describes what
   * they're sending.
   */
  const [voiceLang, setVoiceLang] = useState<string | undefined>(undefined)
  /** Announced via aria-live so screen-reader users hear what Meera transcribed. */
  const [transcriptionAnnouncement, setTranscriptionAnnouncement] = useState<string | null>(null)
  const textareaRef = useRef<HTMLTextAreaElement>(null)
  const locked = disabled || sendLocked

  /** Tap a starter template → drop it in the input for editing (never sends). */
  const handleTemplatePick = (template: string) => {
    if (locked) return
    setValue(template)
    // Focus so the user can immediately edit the [blanks]; runs after the
    // value lands so the caret sits at the end of the seeded text.
    requestAnimationFrame(() => {
      const el = textareaRef.current
      if (!el) return
      el.focus()
      el.setSelectionRange(el.value.length, el.value.length)
    })
  }

  /** Starter templates only make sense on a blank composer at the start of a chat. */
  const showTemplates = templates.length > 0 && !locked && value.trim() === ''

  const { supported: voiceInputSupported, phase, start, stop } = useVoiceInput({
    onResult: (cleanedText, lang) => {
      // Edit-first, always. Land the cleaned transcript in the composer's
      // existing value for the user to review and send themselves.
      setVoiceFallback(null)
      setTranscriptionAnnouncement(cleanedText)
      setVoiceLang(lang)
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
    onSend(trimmed, voiceLang)
    setValue('')
    setVoiceFallback(null)
    setTranscriptionAnnouncement(null)
    setVoiceLang(undefined)
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
      {showTemplates && (
        <div className="flex gap-2 overflow-x-auto pb-1 scrollbar-thin" role="group" aria-label="Starter templates">
          {templates.map((template) => (
            <QuickReplyChip key={template} label={template} onClick={() => handleTemplatePick(template)} />
          ))}
        </div>
      )}
      <div className="flex items-end gap-2 rounded-xl border border-meera-border bg-meera-surface p-2">
        <textarea
          ref={textareaRef}
          value={value}
          onChange={(e) => {
            setValue(e.target.value)
            setVoiceLang(undefined)
          }}
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
